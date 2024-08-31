package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentHistory
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class PaymentHistoryService {

    public PaymentHistory save(Payment payment, String nossoNumero, Long boletoBankId) {
        PaymentHistory paymentHistory = new PaymentHistory()

        paymentHistory.payment = payment
        paymentHistory.nossoNumero = nossoNumero
        paymentHistory.boletoBankId = boletoBankId

        paymentHistory.save(flush: true, failOnError: true)

        if (!paymentHistory.boletoBankId) AsaasLogger.warn("PaymentHistory criado sem indicar boletoBankId ${paymentHistory.id}", new Exception("exception criada para gerar stacktrace e facilitar identificacao de fluxo"))

        return paymentHistory
    }
}
