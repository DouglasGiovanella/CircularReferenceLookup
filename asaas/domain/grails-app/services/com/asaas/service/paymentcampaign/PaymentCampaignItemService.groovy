package com.asaas.service.paymentcampaign

import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentcampaign.PaymentCampaignItem
import com.asaas.domain.subscription.Subscription
import grails.transaction.Transactional

@Transactional
class PaymentCampaignItemService {

    public void restoreFromPaymentIfNecessary(Payment payment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.query([deletedOnly: true, payment: payment]).get()
        if (!paymentCampaignItem) return

        restore(paymentCampaignItem)
    }

    public void deleteFromPaymentIfNecessary(Payment payment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.query([payment: payment]).get()
        if (!paymentCampaignItem) return

        delete(paymentCampaignItem)
    }

    public void deleteFromInstallmentIfNecessary(Installment installment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.query([installment: installment]).get()
        if (!paymentCampaignItem) return

        delete(paymentCampaignItem)
    }

    public void deleteFromSubscriptionIfNecessary(Subscription subscription) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.query([subscription: subscription]).get()
        if (!paymentCampaignItem) return

        delete(paymentCampaignItem)
    }

    private void restore(PaymentCampaignItem paymentCampaignItem) {
        paymentCampaignItem.deleted = false
        paymentCampaignItem.save(failOnError: true)
    }

    private void delete(PaymentCampaignItem paymentCampaignItem) {
        paymentCampaignItem.deleted = true
        paymentCampaignItem.save(failOnError: true)
    }
}
