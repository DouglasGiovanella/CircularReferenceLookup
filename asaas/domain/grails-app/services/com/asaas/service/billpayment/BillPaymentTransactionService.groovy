package com.asaas.service.billpayment

import com.asaas.bill.BillPaymentTransactionGateway
import com.asaas.bill.BillPaymentTransactionStatus
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillPaymentTransaction
import grails.transaction.Transactional

@Transactional
class BillPaymentTransactionService {

    public BillPaymentTransaction save(Bill bill, BillPaymentTransactionGateway gateway, Map params) {
        BillPaymentTransaction billPaymentTransaction = new BillPaymentTransaction()

        billPaymentTransaction.bill = bill
        billPaymentTransaction.status = BillPaymentTransactionStatus.AWAITING_CONFIRMATION
        billPaymentTransaction.gateway = gateway
        billPaymentTransaction.externalId = params.externalId

        return billPaymentTransaction.save(failOnError: true)
    }
}
