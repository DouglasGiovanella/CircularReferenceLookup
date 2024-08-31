package com.asaas.service.billingtype

import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.utils.AbTestUtils

import grails.transaction.Transactional

@Transactional
class BillingTypeService {

    public List<BillingType> getAllowedBillingTypeList(Payment payment) {
        List<BillingType> allowedBillingTypeList = []

        if (payment.getDunning()?.hasCreditBureauPartnerProcess()) return [BillingType.BOLETO]

        if (payment.billingType.isCreditCard()) {
            allowedBillingTypeList.add(BillingType.MUNDIPAGG_CIELO)
        } else if (payment.billingType.isUndefined()) {
            allowedBillingTypeList.addAll(getAllowedBillingTypesForUndefined(payment.provider, payment.id))
        } else if (payment.billingType.isPix()) {
            allowedBillingTypeList.add(BillingType.PIX)
        } else {
            allowedBillingTypeList.addAll([BillingType.BOLETO, BillingType.PIX])
        }

        if (allowedBillingTypeList.contains(BillingType.MUNDIPAGG_CIELO) && canUseDebitCardInBillingTypelist(payment)) {
            allowedBillingTypeList.add(BillingType.DEBIT_CARD)
        }

        if (payment.billingType in [BillingType.BOLETO, BillingType.UNDEFINED]) {
            if (allowedBillingTypeList.contains(BillingType.PIX) && !PixUtils.paymentReceivingWithPixEnabled(payment.provider)) {
                allowedBillingTypeList.remove(BillingType.PIX)
            }
        }

        return allowedBillingTypeList
    }

    private Boolean canUseDebitCardInBillingTypelist(Payment payment) {
        if (payment.installment) return false
        if (!(payment.billingType in [BillingType.MUNDIPAGG_CIELO, BillingType.UNDEFINED])) return false
        if (CustomerParameter.getValue(payment.provider, CustomerParameterName.PAYMENT_CHECKOUT_HIDE_DEBIT_CARD)) return false

        return true
    }

    private List<BillingType> getAllowedBillingTypesForUndefined(Customer customer, Long paymentId) {
        List<BillingType> undefinedAllowedBillingTypeList = []
        if (AbTestUtils.hasPaymentWizardUndefinedBillingConfig(customer)) {
            PaymentUndefinedBillingTypeConfig paymentUndefinedBillingTypeConfig = PaymentUndefinedBillingTypeConfig.query([paymentId: paymentId]).get()
            if (paymentUndefinedBillingTypeConfig) {
                if (paymentUndefinedBillingTypeConfig.isBankSlipAllowed) undefinedAllowedBillingTypeList.add(BillingType.BOLETO)
                if (paymentUndefinedBillingTypeConfig.isPixAllowed) undefinedAllowedBillingTypeList.add(BillingType.PIX)
                if (paymentUndefinedBillingTypeConfig.isCreditCardAllowed) undefinedAllowedBillingTypeList.add(BillingType.MUNDIPAGG_CIELO)
            } else {
                undefinedAllowedBillingTypeList.addAll([BillingType.BOLETO, BillingType.PIX])
                if (customer.cpfCnpj) undefinedAllowedBillingTypeList.add(BillingType.MUNDIPAGG_CIELO)
            }
        } else {
            undefinedAllowedBillingTypeList.addAll([BillingType.BOLETO, BillingType.PIX])
            if (customer.cpfCnpj) undefinedAllowedBillingTypeList.add(BillingType.MUNDIPAGG_CIELO)
        }

        return undefinedAllowedBillingTypeList
    }
}

