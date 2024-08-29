package com.asaas.service.checkoutcallbackconfig

import com.asaas.checkoutcallbackconfig.CheckoutCallbackConfigValidator
import com.asaas.domain.customer.Customer
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.checkoutcallbackconfig.SubscriptionCheckoutCallbackConfig
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class SubscriptionCheckoutCallbackConfigService {

    public SubscriptionCheckoutCallbackConfig saveOrUpdate(Subscription subscription, Map callbackData) {
        if (!subscription) throw new RuntimeException("SubscriptionCheckoutCallbackConfigService.saveOrUpdate >> O objeto enviado não é um Subscription válido")

        SubscriptionCheckoutCallbackConfig checkoutCallbackConfig = SubscriptionCheckoutCallbackConfig.query([subscription: subscription]).get()
        if (!checkoutCallbackConfig) {
            checkoutCallbackConfig = new SubscriptionCheckoutCallbackConfig()
            checkoutCallbackConfig.subscription = subscription
        }

        return validateAndSave(checkoutCallbackConfig, callbackData, subscription.provider)
    }

    private SubscriptionCheckoutCallbackConfig validateAndSave(SubscriptionCheckoutCallbackConfig checkoutCallbackConfig, Map callbackData, Customer customer) {
        BusinessValidation businessValidation = CheckoutCallbackConfigValidator.validate(callbackData, customer)
        if (!businessValidation.isValid()) return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, checkoutCallbackConfig)

        if (callbackData.containsKey("autoRedirect")) {
            checkoutCallbackConfig.autoRedirect = callbackData.autoRedirect
        }

        checkoutCallbackConfig.successUrl = callbackData.successUrl
        return checkoutCallbackConfig.save(failOnError: true)
    }
}
