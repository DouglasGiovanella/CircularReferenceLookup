package com.asaas.service.customerdealinfo.creditcarddynamicmcc

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDealInfoCreditCardDynamicMccFeeConfigDeletionService {

    def asyncActionService
    def feeAdminService

    public void applyDefaultFeeConfig() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.APPLY_DEFAULT_CREDIT_CARD_FEE_CONFIG_TO_CHILD_ACCOUNTS)
        if (!asyncActionData) return

        Utils.withNewTransactionAndRollbackOnError({
            Long accountOwnerId = asyncActionData.accountOwnerId
            Long lastAppliedChildAccountId = asyncActionData.lastAppliedChildAccountId

            List<Long> childAccountIdList = listChildAccountIdToApplyDefaultFeeConfig(accountOwnerId, lastAppliedChildAccountId)
            if (!childAccountIdList) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            Map defaultCreditCardFeeConfig = feeAdminService.buildCreditCardDefaultFeeConfig()
            for (Long childAccountId : childAccountIdList) {
                CreditCardFeeConfig creditCardFeeConfig = feeAdminService.updateCreditCardFee(childAccountId, defaultCreditCardFeeConfig, false)
                if (creditCardFeeConfig.hasErrors()) throw new ValidationException(null, creditCardFeeConfig.errors)
            }

            asyncActionService.delete(asyncActionData.asyncActionId)
            saveNextAsyncAction(accountOwnerId, childAccountIdList.last())
        }, [logErrorMessage: "CustomerDealInfoCreditCardDynamicMccFeeConfigDeletionService.applyDefaultFeeConfig >> Erro ao aplicar taxas padrões de cartão de crédito para contas filhas. AsyncActionId: [${asyncActionData.asyncActionId}]",
             onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
    }

    private List<Long> listChildAccountIdToApplyDefaultFeeConfig(Long accountOwnerId, Long lastChildAccountReplicatedId) {
        Map search = [:]
        search.column = "id"
        search.accountOwnerId = accountOwnerId
        if (lastChildAccountReplicatedId) search."id[gt]" = lastChildAccountReplicatedId

        final Integer maxItemsPerCycle = 2000
        List<Long> childAccountIdList = Customer.notDisabledAccounts(search).list(max: maxItemsPerCycle)

        return childAccountIdList
    }

    private void saveNextAsyncAction(Long accountOwnerId, Long lastAppliedChildAccountId) {
        Map actionData = [accountOwnerId: accountOwnerId, lastAppliedChildAccountId: lastAppliedChildAccountId]
        asyncActionService.save(AsyncActionType.APPLY_DEFAULT_CREDIT_CARD_FEE_CONFIG_TO_CHILD_ACCOUNTS, actionData)
    }
}
