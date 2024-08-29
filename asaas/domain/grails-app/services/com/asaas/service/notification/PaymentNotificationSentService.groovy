package com.asaas.service.notification

import com.asaas.domain.notification.PaymentNotificationSent
import com.asaas.domain.payment.Payment
import com.asaas.notification.NotificationType

import grails.transaction.Transactional

@Transactional
class PaymentNotificationSentService {

    def chargedFeeService
    def featureFlagService

    public void save(Payment payment, NotificationType type, Long externalId) {
        PaymentNotificationSent notificationSent = new PaymentNotificationSent()
        notificationSent.payment = payment
        notificationSent.type = type
        notificationSent.externalId = externalId

        if (!featureFlagService.isNotificationRequestExternalProcessingEnabled()) {
            notificationSent.deleted = true
            notificationSent.save(failOnError: true)
            return
        }

        notificationSent.save(failOnError: true)

        if (type.isWhatsApp()) {
            chargedFeeService.saveInstantNotificationSentFee(payment)
            return
        }
        if (type.isPhoneCall()) {
            chargedFeeService.savePhoneCallNotificationSentFee(payment)
            return
        }

        if (payment.hasAlreadyBeenConfirmed()) {
            chargedFeeService.savePaymentSmsNotificationFeeIfNecessary(payment)
            chargedFeeService.savePaymentMessagingNotificationFeeIfNecessary(payment)
        }
    }
}
