package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.BankSlipFee
import com.asaas.service.accountowner.BankSlipFeeParameterService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountBankSlipFeeReplicationService {

    def asyncActionService
    def bankSlipFeeParameterService
    def childAccountParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountBankSlipFeeReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: BankSlipFee.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountBankSlipFeeReplicationService.start >> Erro no processamento da replicação das taxas de BankSlipFee [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String fieldName = getChildAccountParameterFieldName(childAccountParameter)
        Object fieldValue = getChildAccountParameterFieldValue(childAccountParameter)

        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, fieldName, fieldValue, childAccountParameter.type)

        final Integer maximumNumberOfDataPerReplication = 2000
        List<Long> customerIdList = BankSlipFee.query(search).list(max: maximumNumberOfDataPerReplication)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            bankSlipFeeParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String fieldName = getChildAccountParameterFieldName(childAccountParameter)
        Object value = getChildAccountParameterFieldValue(childAccountParameter)

        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, fieldName, value, childAccountParameter.type)
        search.exists = true

        return BankSlipFee.query(search).get().asBoolean()
    }

    private String getChildAccountParameterFieldName(ChildAccountParameter childAccountParameter) {
        if (BankSlipFeeParameterService.DISCOUNT_EXPIRATION_TYPE_LIST.contains(childAccountParameter.name)) return "discountExpiration"

        return childAccountParameter.name
    }

    private Object getChildAccountParameterFieldValue(ChildAccountParameter childAccountParameter) {
        final String discountExpirationDateFieldName = "discountExpirationDate"
        final String discountExpirationDateInMonthsName = "discountExpirationInMonths"

        if (childAccountParameter.name == discountExpirationDateFieldName) return CustomDateUtils.fromStringDatabaseDateFormat(childAccountParameter.value)
        if (childAccountParameter.name == discountExpirationDateInMonthsName) return CustomDateUtils.addMonths(new Date().clearTime(), Utils.toInteger(childAccountParameter.value))

        return childAccountParameter.value
    }
}
