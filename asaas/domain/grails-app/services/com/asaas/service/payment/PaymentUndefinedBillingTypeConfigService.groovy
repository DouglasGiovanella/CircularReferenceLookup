package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentUndefinedBillingTypeConfigService {

    public PaymentUndefinedBillingTypeConfig save(Payment payment, Map paymentUndefinedBillingTypeConfigMap) {
        PaymentUndefinedBillingTypeConfig paymentUndefinedBillingTypeConfig = PaymentUndefinedBillingTypeConfig.query([paymentId: payment.id, includeDeleted: true]).get()
        if (!paymentUndefinedBillingTypeConfig) {
            paymentUndefinedBillingTypeConfig = new PaymentUndefinedBillingTypeConfig()
            paymentUndefinedBillingTypeConfig.payment = payment
        }

        if (!paymentUndefinedBillingTypeConfigMap) paymentUndefinedBillingTypeConfigMap = [isBankSlipAllowed: true, isCreditCardAllowed: true, isPixAllowed: true]

        paymentUndefinedBillingTypeConfig.isBankSlipAllowed = Utils.toBoolean(paymentUndefinedBillingTypeConfigMap?.isBankSlipAllowed)
        paymentUndefinedBillingTypeConfig.isCreditCardAllowed = Utils.toBoolean(paymentUndefinedBillingTypeConfigMap?.isCreditCardAllowed)
        paymentUndefinedBillingTypeConfig.isPixAllowed = PixUtils.paymentReceivingWithPixEnabled(payment.provider) ? Utils.toBoolean(paymentUndefinedBillingTypeConfigMap?.isPixAllowed) : false
        paymentUndefinedBillingTypeConfig.deleted = false

        paymentUndefinedBillingTypeConfig.save(failOnError: true)

        return paymentUndefinedBillingTypeConfig
    }

    public void deleteIfNecessary(Long paymentId) {
        PaymentUndefinedBillingTypeConfig paymentUndefinedBillingTypeConfig = PaymentUndefinedBillingTypeConfig.query([paymentId: paymentId]).get()
        if (!paymentUndefinedBillingTypeConfig) return

        paymentUndefinedBillingTypeConfig.deleted = true
        paymentUndefinedBillingTypeConfig.save(failOnError: true)
    }
}
