package com.asaas.service.customerbalance

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDailyBalanceConsolidationRecalculateService {

    def asyncActionService

    public void saveAsyncActionWhenDeleteFinancialTransaction(Customer customer, Date transactionDate) {
        Map search = [:]
        search.column = "id"
        search.customer = customer
        search."consolidationDate[ge]" = transactionDate

        List<Long> balanceConsolidationIdList = CustomerDailyBalanceConsolidation.query(search).list()

        for (Long balanceConsolidationId : balanceConsolidationIdList) {
            asyncActionService.saveRecalculateCustomerDailyBalanceConsolidation(balanceConsolidationId)
        }
    }

    public void recalculate() {
        final Integer maxItemsPerCycle = 1

        for (Map asyncActionData : asyncActionService.listPendingRecalculateCustomerDailyBalanceConsolidation(maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError ({
                CustomerDailyBalanceConsolidation balanceConsolidation = CustomerDailyBalanceConsolidation.get(Utils.toLong(asyncActionData.balanceConsolidationId))
                balanceConsolidation.consolidatedBalance = FinancialTransaction.sumValue([customerId: balanceConsolidation.customer.id, "transactionDate[le]": CustomDateUtils.setTimeToEndOfDay(balanceConsolidation.consolidationDate)]).get()
                balanceConsolidation.save(failOnError: true)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CustomerDailyBalanceConsolidationService.recalculate >> Erro ao recalcular o saldo consolidado ID [${asyncActionData.balanceConsolidationId}]. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }
}
