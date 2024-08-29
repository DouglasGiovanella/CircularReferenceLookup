package com.asaas.service.integration.cerc

import com.asaas.creditcard.CreditCardBrand
import com.asaas.debitcard.DebitCardBrand
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.receivableunit.PaymentArrangement
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class PaymentArrangementService {

    def cieloManagerService

    public PaymentArrangement getPaymentArrangement(Payment payment) {
        return getPaymentArrangement(payment, true)
    }

    public PaymentArrangement getPaymentArrangement(Payment payment, Boolean queryCieloForUnknownBrand) {
        if (!payment.isCreditCard() && !payment.isDebitCard()) return null
        if (!payment.status.hasBeenConfirmed()) return null

        if (payment.isDebitCard()) return getPaymentArrangementFromDebitCard(payment)
        return getPaymentArrangementFromCreditCard(payment, queryCieloForUnknownBrand)
    }

    private PaymentArrangement getPaymentArrangementFromDebitCard(Payment payment) {
        DebitCardBrand debitCardBrand = payment.getLastDebitCardAuthorizationInfo().brand

        try {
            return PaymentArrangement.getPaymentArrangementFromDebitCard(debitCardBrand)
        } catch (NotImplementedException notImplementedException) {
            AsaasLogger.error("PaymentArrangementService.getPaymentArrangement >> Arranjo de pagamento de cartão de débito não implementado [paymentId: ${payment.id}, cardBrand: ${debitCardBrand}]", notImplementedException)
        }
    }

    private PaymentArrangement getPaymentArrangementFromCreditCard(Payment payment, Boolean queryCieloForUnknownBrand) {
        if (!payment.billingInfo) return null
        CreditCardBrand creditCardBrand

        try {
            creditCardBrand = payment.billingInfo.creditCardInfo.brand

            if (queryCieloForUnknownBrand && creditCardBrand.isUnknown()) creditCardBrand = cieloManagerService.getBrand(payment.billingInfo.creditCardInfo.bin)
            return PaymentArrangement.getPaymentArrangementFromCreditCard(creditCardBrand)
        } catch (NotImplementedException notImplementedException) {
            AsaasLogger.error("PaymentArrangementService.getPaymentArrangement >> Arranjo de pagamento de cartão de crédito não implementado [paymentId: ${payment.id}, cardBrand: ${creditCardBrand}]", notImplementedException)
            return null
        }
    }
}
