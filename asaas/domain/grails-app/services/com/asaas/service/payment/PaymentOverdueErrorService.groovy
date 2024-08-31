package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentOverdueError
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentOverdueErrorService {

    public PaymentOverdueError save(Payment payment, String overdueProcessError) {
        PaymentOverdueError  paymentOverdueError = new PaymentOverdueError()
        paymentOverdueError.payment = payment
        paymentOverdueError.error = Utils.truncateString(overdueProcessError, 255)
        paymentOverdueError.save(failOnError: true)

        return paymentOverdueError
    }
}
