package com.asaas.service.nexinvoice

import com.asaas.domain.financialtransaction.FinancialTransaction

import grails.transaction.Transactional

@Transactional
class NexinvoiceFinanceService {

    public BigDecimal getTotalBalance(Long customerId) {
        return FinancialTransaction.getCustomerBalance(customerId)
    }
}
