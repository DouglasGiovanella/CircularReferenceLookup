package com.asaas.service.payment

import com.asaas.domain.payment.PaymentDunningAccountability
import com.asaas.domain.payment.PartialPayment

import grails.transaction.Transactional

@Transactional
class PartialPaymentService {

    def chargedFeeService
    def financialTransactionService

    private PartialPayment save(PaymentDunningAccountability accountability) {
        PartialPayment partialPayment = new PartialPayment()
        partialPayment.accountability = accountability
        partialPayment.payment = accountability.dunning.payment
        partialPayment.value = accountability.value
        partialPayment.customer = accountability.dunning.payment.provider
        partialPayment.save(flush: true, failOnError: true)

        financialTransactionService.savePartialPayment(partialPayment)
        chargedFeeService.saveDunningAccountabilityFee(accountability)

        return partialPayment
    }
}
