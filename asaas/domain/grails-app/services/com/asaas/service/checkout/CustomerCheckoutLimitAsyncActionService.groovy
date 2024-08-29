package com.asaas.service.checkout

import com.asaas.domain.customer.Customer
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerCheckoutLimitAsyncActionService {

    def customerCheckoutLimitService
    def asyncActionService

    public void processRefundDailyLimit() {
        for (Map asyncActionData in asyncActionService.listPendingRefundCustomerCheckoutDailyLimit()) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(asyncActionData.customerId)
                BigDecimal value = asyncActionData.value
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate)

                if (transactionDate == new Date().clearTime()) {
                    customerCheckoutLimitService.refundDailyLimit(customer, value)
                    asyncActionService.setAsDone(asyncActionData.asyncActionId)
                } else {
                    asyncActionService.setAsCancelled(asyncActionData.asyncActionId)
                }
            }, [logErrorMessage: "CustomerCheckoutLimitAsyncActionService.processRefundDailyLimit() -> Erro ao processar AsyncAction [${asyncActionData.asyncActionId}]", onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }
}
