package com.asaas.service.subscription

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.subscription.Subscription
import grails.transaction.Transactional

@Transactional
class SubscriptionNotificationService {

    def customerAlertNotificationService
    def messageService
    def subscriptionService

    public void notifyCustomerAboutSubscriptionEndingIfNecessary(Subscription subscription) {
        if (!shouldNotifySubscriptionEnding(subscription)) return

        Date lastPaymentDueDate = Payment.query([column: "dueDate", subscriptionId: subscription.id, sort: "id", order:"desc"]).get()

        customerAlertNotificationService.notifySubscriptionEnding(subscription)
        messageService.sendNotificationToCustomerAboutSubscriptionEnding(subscription, lastPaymentDueDate)
    }

    private Boolean shouldNotifySubscriptionEnding(Subscription subscription) {
        if (!subscription.status.isActive()) return false
        if (!subscription.maxPayments && !subscription.endDate) return false
        if (!subscription.cycle.isAllowedToNotifyOnSubscriptionEnding()) return false
        if (!CustomerParameter.getValue(subscription.provider, CustomerParameterName.ENABLE_NOTIFY_USERS_ON_SUBSCRIPTION_ENDING)) return false
        if (subscriptionService.hasPaymentsToBeCreated(subscription)) return false

        return true
    }
}
