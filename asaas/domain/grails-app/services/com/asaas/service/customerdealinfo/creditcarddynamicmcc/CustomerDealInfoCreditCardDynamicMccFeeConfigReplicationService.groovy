package com.asaas.service.customerdealinfo.creditcarddynamicmcc

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigMccRepository
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.feenegotiation.FeeNegotiationMcc
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationMccRepository
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerDealInfoCreditCardDynamicMccFeeConfigReplicationService {

    def asyncActionService
    def creditCardFeeConfigAdminService
    def creditCardFeeConfigService
    def customerDealInfoCreditCardDynamicMccFeeConfigService
    def customerInteractionService

    public void replicate() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.CHILD_ACCOUNT_CREDIT_CARD_DYNAMIC_MCC_FEE_CONFIG_REPLICATION)
        if (!asyncActionData) return

        Utils.withNewTransactionAndRollbackOnError({
            Long accountOwnerId = asyncActionData.accountOwnerId
            Long feeConfigGroupId = asyncActionData.feeConfigGroupId
            Long lastChildAccountReplicatedId = asyncActionData.lastChildAccountReplicatedId

            List<Long> childAccountIdList = listChildAccountIdToReplicate(accountOwnerId, feeConfigGroupId, lastChildAccountReplicatedId)
            if (!childAccountIdList) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            applyFeeConfigForChildAccounts(childAccountIdList, feeConfigGroupId)

            asyncActionService.delete(asyncActionData.asyncActionId)

            saveNextAsyncAction(accountOwnerId, feeConfigGroupId, childAccountIdList.last())
        }, [logErrorMessage: "CustomerDealInfoCreditCardDynamicMccFeeConfigReplicationService.replicate >> Erro ao replicar taxas de Cartão de Crédito com MCC dinâmico. AsyncActionId: [${asyncActionData.asyncActionId}]",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
    }

    public void replicateToChildAccountIfPossible(Customer accountOwner, Customer childAccount) {
        if (!shouldReplicateToChildAccount(accountOwner, childAccount)) return

        Map search = buildFeeNegotiationSearch(childAccount.getMcc(), accountOwner)
        Long feeConfigGroupId = CustomerDealInfoFeeConfigMccRepository.query(search).column("feeConfigGroup.id").get()

        Map feeConfigInfo = customerDealInfoCreditCardDynamicMccFeeConfigService.buildFeeConfigItemInfo(feeConfigGroupId, false)
        applyCreditCardDynamicMccFeeConfig(childAccount, feeConfigInfo)
    }

    public Boolean shouldReplicateToChildAccount(Customer accountOwner, Customer childAccount) {
        if (!accountOwner) return false

        String mcc = childAccount.getMcc()
        if (!mcc) return false

        Boolean hasNegotiation = CustomerDealInfoFeeConfigGroupRepository.query(["customerId": accountOwner.id, "replicationType": FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC]).exists()
        if (!hasNegotiation) return false

        Map search = buildFeeNegotiationSearch(mcc, accountOwner)
        Boolean hasMccNegotiation = CustomerDealInfoFeeConfigMccRepository.query(search).exists()
        if (!hasMccNegotiation) return false

        return true
    }

    private void applyCreditCardDynamicMccFeeConfig(Customer childAccount, Map feeConfigInfo) {
        creditCardFeeConfigService.save(childAccount, feeConfigInfo)
        customerInteractionService.saveUpdateCreditCardFee(childAccount, feeConfigInfo, true)
    }

    private void applyFeeConfigForChildAccounts(List<Long> childAccountIdList, Long feeConfigGroupId) {
        Map feeConfigInfo = customerDealInfoCreditCardDynamicMccFeeConfigService.buildFeeConfigItemInfo(feeConfigGroupId, false)

        Utils.forEachWithFlushSession(childAccountIdList, 50, { Long childAccountId ->
            Customer childAccount = Customer.read(childAccountId)
            applyCreditCardDynamicMccFeeConfig(childAccount, feeConfigInfo)
        })
    }

    private List<Long> listChildAccountIdToReplicate(Long accountOwnerId, Long feeConfigGroupId, Long lastChildAccountReplicatedId) {
        Map search = [:]
        search.column = "id"
        search.accountOwnerId = accountOwnerId
        if (lastChildAccountReplicatedId) search."id[gt]" = lastChildAccountReplicatedId

        final Integer maxItemsPerCycle = 500
        List<Long> childAccountIdList = Customer.notDisabledAccounts(search).list(max: maxItemsPerCycle)

        if (!childAccountIdList) return []

        List<String> negotiatedMccList = CustomerDealInfoFeeConfigMccRepository.query([feeConfigGroupId: feeConfigGroupId]).column("feeNegotiationMcc.mcc").list()
        Customer accountOwner = Customer.read(accountOwnerId)

        List<Long> childAccountWithNegotiationIdList = []
        Utils.forEachWithFlushSession(childAccountIdList, 50, { Long childAccountId ->
            Customer childAccount = Customer.read(childAccountId)
            String mcc = findMccForReplication(childAccount.getMcc(), accountOwner)

            if (negotiatedMccList.contains(mcc)) childAccountWithNegotiationIdList.add(childAccountId)
        })

        if (!childAccountWithNegotiationIdList) {
            saveNextAsyncAction(accountOwnerId, feeConfigGroupId, childAccountIdList.last())
            return []
        }

        return listChildAccountIdWithPendingFeeConfigReplication(accountOwnerId, childAccountWithNegotiationIdList, feeConfigGroupId)
    }

    private List<Long> listChildAccountIdWithPendingFeeConfigReplication(Long accountOwnerId, List<Long> childAccountIdList, Long feeConfigGroupId) {
        Map creditCardCommissionConfig = creditCardFeeConfigAdminService.findCreditCardPaymentCommissionConfig(accountOwnerId)
        Map feeConfigInfo = customerDealInfoCreditCardDynamicMccFeeConfigService.buildFeeConfigItemInfo(feeConfigGroupId, false)

        List<String> productFeeTypeList = ["fixedFee", "upfrontFee", "upToSixInstallmentsFee", "upToTwelveInstallmentsFee"]

        return CreditCardFeeConfig.createCriteria().list {
            projections {
                property "customer.id"
            }

            eq("deleted", false)
            "in"("customer.id", childAccountIdList)

            or {
                for (String productFeeType : productFeeTypeList) {
                    BigDecimal productFeeValue = getProductFeeValue(productFeeType, feeConfigInfo[productFeeType], creditCardCommissionConfig)

                    or {
                        ne("$productFeeType", productFeeValue)
                        isNull("$productFeeType")
                    }
                }
            }
        }
    }

    private BigDecimal getProductFeeValue(String productFeeType, BigDecimal productFeeValue, Map creditCardCommissionConfig) {
        BigDecimal feeValue = Utils.toBigDecimal(productFeeValue)

        if (feeValue && CreditCardFeeConfig.COMMISSIONABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST.contains(productFeeType)) {
            BigDecimal creditCardCommissionPercentage = creditCardFeeConfigAdminService.findCreditCardCommissionPercentageFromCreditCardFeeConfigColumnName(creditCardCommissionConfig, "productFeeType")
            feeValue += creditCardCommissionPercentage
        }

        return feeValue
    }

    private void saveNextAsyncAction(Long accountOwnerId, Long feeConfigGroupId, Long lastChildAccountReplicatedId) {
        Map actionData = [accountOwnerId: accountOwnerId, feeConfigGroupId: feeConfigGroupId, lastChildAccountReplicatedId: lastChildAccountReplicatedId]
        asyncActionService.save(AsyncActionType.CHILD_ACCOUNT_CREDIT_CARD_DYNAMIC_MCC_FEE_CONFIG_REPLICATION, actionData)
    }

    private Map buildFeeNegotiationSearch(String mcc, Customer accountOwner) {
        Map search = [:]
        search.replicationType = FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC
        search.customer = accountOwner
        search.mcc = findMccForReplication(mcc, accountOwner)

        return search
    }

    private String findMccForReplication(String mcc, Customer customer) {
        if (customer.accountOwner) throw new BusinessException("Para buscar por mcc dinâmico, a conta informada deve ser uma conta pai.")

        Boolean isMccNegotiable = FeeNegotiationMccRepository.query(["mcc": mcc]).exists()
        if (!isMccNegotiable) return FeeNegotiationMcc.OTHERS_NOT_NEGOTIABLE

        List<String> negotiableMccList = CustomerDealInfoFeeConfigMccRepository.findAvailableNegotiableMcc(customer.id)

        if (negotiableMccList.contains(mcc)) return FeeNegotiationMcc.OTHERS_NEGOTIABLE

        return mcc
    }
}
