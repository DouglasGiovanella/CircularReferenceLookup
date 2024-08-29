package com.asaas.service.payment

import com.asaas.domain.notification.NotificationDispatcherCustomerAccount
import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class PaymentNotificationRequestService {

    def featureFlagService
    def notificationDispatcherManualNotificationManagerService
    def notificationRequestService

    public void resendNotificationManually(Payment payment) {
        if (canUseNotificationDispatcher(payment.customerAccount.id)) {
            notificationDispatcherManualNotificationManagerService.create(payment)
        }
        notificationRequestService.manuallyResendPaymentNotification(payment)
    }

    private Boolean canUseNotificationDispatcher(Long customerAccountId) {
        if (!featureFlagService.isNotificationRequestExternalProcessingEnabled()) return false

        return NotificationDispatcherCustomerAccount.query([exists: true, customerAccountId: customerAccountId]).get().asBoolean()
    }
}
