package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCreditCardFeeReplicationService {

    def asyncActionService
    def creditCardFeeConfigAdminService
    def creditCardFeeConfigParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountCreditCardFeeReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CreditCardFeeConfig.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCreditCardFeeReplicationService.start >> Erro no processamento da replicação das taxas de CreditCardFeeConfig [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Object fieldValue = creditCardFeeConfigParameterService.buildFieldValue(childAccountParameter)
        Object fieldName = creditCardFeeConfigParameterService.buildFieldName(childAccountParameter)

        Map search = buildQueryParamsForCreditCardFeeReplication(accountOwnerId, fieldName, fieldValue)

        final Integer maximumNumberOfDataPerReplication = 2000
        List<Long> customerIdList = CreditCardFeeConfig.query(search).list(max: maximumNumberOfDataPerReplication)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            creditCardFeeConfigParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Object fieldValue = creditCardFeeConfigParameterService.buildFieldValue(childAccountParameter)
        Object fieldName = creditCardFeeConfigParameterService.buildFieldName(childAccountParameter)

        Map search = buildQueryParamsForCreditCardFeeReplication(accountOwnerId, fieldName, fieldValue)
        search.exists = true

        return CreditCardFeeConfig.query(search).get().asBoolean()
    }

    private Map buildQueryParamsForCreditCardFeeReplication(Long accountOwnerId, String fieldName, value) {
        Map search = [column: "customer.id", accountOwnerId: accountOwnerId]

        if (!value && CreditCardFeeConfig.NULLABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST.contains(fieldName)) {
            search."${fieldName}[isNotNull]" = true
            return search
        }

        if (value && CreditCardFeeConfig.COMMISSIONABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST.contains(fieldName)) {
            Map creditCardPaymentCommissionConfig = creditCardFeeConfigAdminService.findCreditCardPaymentCommissionConfig(accountOwnerId)
            BigDecimal creditCardCommissionPercentage = creditCardFeeConfigAdminService.findCreditCardCommissionPercentageFromCreditCardFeeConfigColumnName(creditCardPaymentCommissionConfig, fieldName)
            search."${fieldName}[ne]" = Utils.toBigDecimal(value) + creditCardCommissionPercentage

            return search
        }

        search."${fieldName}[ne]" = value

        return search
    }
}
