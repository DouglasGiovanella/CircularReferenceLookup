package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class PaymentEventListenerService {

    def hermesPaymentSemaphoreManagerService
    def originRequesterInfoService
    def paymentAnticipableInfoService
    def pixPaymentInfoService
    def receivableAnticipationValidationService
    def riskAnalysisPaymentService

    public void onCreated(Payment payment) {
        if (!payment.onReceiving) {
            paymentAnticipableInfoService.save(payment)
            hermesPaymentSemaphoreManagerService.saveAsync(payment.id)
            pixPaymentInfoService.saveIfNecessary(payment)
        }

        riskAnalysisPaymentService.analyzePaymentIfNecessary(payment)
        originRequesterInfoService.save(payment)
    }

    public void onRestore(Payment payment) {
        receivableAnticipationValidationService.onPaymentRestore(payment)
        riskAnalysisPaymentService.analyzePaymentIfNecessary(payment)
    }
}
