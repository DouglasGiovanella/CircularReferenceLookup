package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixCreditFee
import com.asaas.service.accountowner.PixCreditFeeParameterService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountPixCreditFeeReplicationService {

    def asyncActionService
    def childAccountParameterService
    def pixCreditFeeParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountPixCreditFeeReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: PixCreditFee.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountPixCreditFeeReplicationService.start >> Erro no processamento da replicação das taxas de PixCreditFee [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String fieldName = getChildAccountParameterFieldName(childAccountParameter)
        Object fieldValue = getChildAccountParameterFieldValue(childAccountParameter)

        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, fieldName, fieldValue, childAccountParameter.type)

        final Integer maximumNumberOfDataPerReplication = 2000
        List<Long> customerIdList = PixCreditFee.query(search).list(max: maximumNumberOfDataPerReplication)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            pixCreditFeeParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String fieldName = getChildAccountParameterFieldName(childAccountParameter)
        Object fieldValue = getChildAccountParameterFieldValue(childAccountParameter)

        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, fieldName, fieldValue, childAccountParameter.type)
        search.exists = true

        return PixCreditFee.query(search).get().asBoolean()
    }

    private String getChildAccountParameterFieldName(ChildAccountParameter childAccountParameter) {
        if (PixCreditFeeParameterService.FIXED_FEE_DISCOUNT_EXPIRATION_TYPE_LIST.contains(childAccountParameter.name)) return "discountExpiration"

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
