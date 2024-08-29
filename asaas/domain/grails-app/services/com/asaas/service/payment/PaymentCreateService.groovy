package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class PaymentCreateService {

    def paymentService

    public Payment save(Map params, Boolean failOnError, Boolean notify) {
        return paymentService.save(params, failOnError, notify)
    }

}
