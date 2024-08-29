package com.asaas.service.customerbalance

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyPartialBalance
import com.asaas.domain.financialtransaction.FinancialTransaction
import grails.transaction.Transactional

@Transactional
class CustomerDailyPartialBalanceRecalculateService {

    public void recalculateWhenDeleteFinancialTransaction(Customer customer) {
        CustomerDailyPartialBalance partialBalance = CustomerDailyPartialBalance.query([customer: customer]).get()
        if (!partialBalance) return

        FinancialTransaction lastFinancialTransaction = FinancialTransaction.query([customerId: customer.id, sort: "id", order: "desc"]).get()
        if (!lastFinancialTransaction) {
            partialBalance.deleted = true
            partialBalance.save(failOnError: true)
            return
        }

        partialBalance.balance = FinancialTransaction.sumValue([provider: customer, "id[le]": lastFinancialTransaction.id]).get()
        partialBalance.balanceDate = new Date()
        partialBalance.lastAnalyzedFinancialTransaction = lastFinancialTransaction
        partialBalance.save(failOnError: true)
    }
}
