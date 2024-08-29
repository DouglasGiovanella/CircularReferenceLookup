package com.asaas.service.bill

import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillAsaasPayment
import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class BillAsaasPaymentService {

    public BillAsaasPayment save(Bill bill, Payment payment) {
        BillAsaasPayment billAsaasPayment = new BillAsaasPayment()
        billAsaasPayment.bill = bill
        billAsaasPayment.payment = payment
        billAsaasPayment.save(failOnError: true)

        return billAsaasPayment
    }
}
