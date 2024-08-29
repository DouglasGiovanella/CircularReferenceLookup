package com.asaas.service.customercommission

import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.FinancialTransactionCustomerCommission
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialTransactionCustomerCommissionService {

    public void save(List<CustomerCommission> customerCommissionList, FinancialTransaction transaction) {
        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(customerCommissionList, flushEvery, { CustomerCommission customerCommission ->
            FinancialTransactionCustomerCommission transactionCommission = new FinancialTransactionCustomerCommission()
            transactionCommission.financialTransaction = transaction
            transactionCommission.customerCommission = customerCommission
            transactionCommission.save(failOnError: true)
        })
    }
}
