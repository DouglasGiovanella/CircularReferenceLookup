package com.asaas.service.checkoutcallbackconfig

import com.asaas.checkoutcallbackconfig.CheckoutCallbackConfigValidator
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.checkoutcallbackconfig.PaymentCheckoutCallbackConfig
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PaymentCheckoutCallbackConfigService {

    public PaymentCheckoutCallbackConfig save(Payment payment, Map callbackData) {
        if (!payment) throw new RuntimeException("[PaymentCheckoutCallbackConfigService save] O objeto enviado não é um Payment válido")

        PaymentCheckoutCallbackConfig checkoutCallbackConfig = PaymentCheckoutCallbackConfig.query([payment: payment]).get()
        if (!checkoutCallbackConfig) {
            checkoutCallbackConfig = new PaymentCheckoutCallbackConfig()
            checkoutCallbackConfig.payment = payment
        }

        return validateAndSave(checkoutCallbackConfig, callbackData, payment.provider)
    }

    public PaymentCheckoutCallbackConfig save(PaymentCampaign paymentCampaign, Map callbackData) {
        if (!paymentCampaign) throw new RuntimeException("[PaymentCheckoutCallbackConfigService save] O objeto enviado não é um PaymentCampaign válido")

        PaymentCheckoutCallbackConfig checkoutCallbackConfig = PaymentCheckoutCallbackConfig.query([paymentCampaign: paymentCampaign]).get()
        if (!checkoutCallbackConfig) {
            checkoutCallbackConfig = new PaymentCheckoutCallbackConfig()
            checkoutCallbackConfig.paymentCampaign = paymentCampaign
        }

        return validateAndSave(checkoutCallbackConfig, callbackData, paymentCampaign.customer)
    }

    private PaymentCheckoutCallbackConfig validateAndSave(PaymentCheckoutCallbackConfig checkoutCallbackConfig, Map callbackData, Customer customer) {
        BusinessValidation businessValidation = CheckoutCallbackConfigValidator.validate(callbackData, customer)
        if (!businessValidation.isValid()) return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, checkoutCallbackConfig)

        if (callbackData.containsKey("autoRedirect")) {
            checkoutCallbackConfig.autoRedirect = callbackData.autoRedirect
        }

        checkoutCallbackConfig.successUrl = callbackData.successUrl
        return checkoutCallbackConfig.save(failOnError: true)
    }
}
