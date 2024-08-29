package com.asaas.service.subscription

import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.SubscriptionUndefinedBillingTypeConfig
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class SubscriptionUndefinedBillingTypeConfigService {

    public SubscriptionUndefinedBillingTypeConfig save(Subscription subscription, Map params) {
        SubscriptionUndefinedBillingTypeConfig subscriptionUndefinedBillingTypeConfig = SubscriptionUndefinedBillingTypeConfig.query([subscriptionId: subscription.id, includeDeleted: true]).get()
        if (!subscriptionUndefinedBillingTypeConfig) {
            subscriptionUndefinedBillingTypeConfig = new SubscriptionUndefinedBillingTypeConfig()
            subscriptionUndefinedBillingTypeConfig.subscription = subscription
        }

        subscriptionUndefinedBillingTypeConfig.isBankSlipAllowed = Utils.toBoolean(params?.isBankSlipAllowed)
        subscriptionUndefinedBillingTypeConfig.isCreditCardAllowed = Utils.toBoolean(params?.isCreditCardAllowed)
        subscriptionUndefinedBillingTypeConfig.isPixAllowed = PixUtils.paymentReceivingWithPixEnabled(subscription.provider) ? Utils.toBoolean(params?.isPixAllowed) : false
        subscriptionUndefinedBillingTypeConfig.deleted = false

        subscriptionUndefinedBillingTypeConfig.save(failOnError: true)

        return subscriptionUndefinedBillingTypeConfig
    }

    public void deleteIfNecessary(Long subscriptionId) {
        SubscriptionUndefinedBillingTypeConfig subscriptionUndefinedBillingTypeConfig = SubscriptionUndefinedBillingTypeConfig.query([subscriptionId: subscriptionId]).get()
        if (!subscriptionUndefinedBillingTypeConfig) return

        subscriptionUndefinedBillingTypeConfig.deleted = true
        subscriptionUndefinedBillingTypeConfig.save(failOnError: true)
    }

    public Map getUndefinedBillingTypeConfigMap(Long subscriptionId) {
        SubscriptionUndefinedBillingTypeConfig subscriptionUndefinedBillingTypeConfig = SubscriptionUndefinedBillingTypeConfig.query([subscriptionId: subscriptionId]).get()
        if (!subscriptionUndefinedBillingTypeConfig) return [:]

        return [isBankSlipAllowed: subscriptionUndefinedBillingTypeConfig.isBankSlipAllowed,
                isCreditCardAllowed: subscriptionUndefinedBillingTypeConfig.isCreditCardAllowed,
                isPixAllowed: subscriptionUndefinedBillingTypeConfig.isPixAllowed]
    }
}
