package com.asaas.service.customer

import com.asaas.domain.customer.CustomerFirstConfirmedPayment
import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class CustomerFirstConfirmedPaymentService {

    public void save(Payment payment) {
        CustomerFirstConfirmedPayment customerFirstConfirmedPayment = new CustomerFirstConfirmedPayment()
        customerFirstConfirmedPayment.customer = payment.provider
        customerFirstConfirmedPayment.payment = payment

        customerFirstConfirmedPayment.save(failOnError: true)
    }
}
