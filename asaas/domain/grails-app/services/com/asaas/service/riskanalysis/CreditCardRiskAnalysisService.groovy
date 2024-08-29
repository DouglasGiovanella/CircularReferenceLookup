package com.asaas.service.riskanalysis

import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.creditcard.CreditCardTransactionOrigin
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentOriginRequesterInfo
import com.asaas.domain.paymentcampaign.PaymentCampaign
import grails.transaction.Transactional

@Transactional
class CreditCardRiskAnalysisService {

    def paymentAdminService

    public Map buildCreditCardRiskAnalysisInfo(Long paymentId) {
        Payment payment = Payment.read(paymentId)

        Map model = [billingInfo: payment.billingInfo]

        CreditCardTransactionOrigin creditCardTransactionOrigin = getCreditCardTransactionOrigin(payment)

        if (creditCardTransactionOrigin) {
            model.creditCardTransactionOrigin = creditCardTransactionOrigin
            model.countSameCardTransactions = countSameCardTransactions(payment)
            model.countSameDeviceTransactions = 0

            if (creditCardTransactionOrigin.device) {
                model.countSameDeviceTransactions = paymentAdminService.countAdmin([device: creditCardTransactionOrigin.device])
            }

            model.paymentRemoteIp = getPaymentCreateRemoteIp(payment)
            model.creditCardTransactionAnalysisList = getCreditCardTransactionAnalysis(payment)
        }

        return model
    }

    private CreditCardTransactionOrigin getCreditCardTransactionOrigin(Payment payment) {
        if (payment.installment) {
            return CreditCardTransactionOrigin.query([installment: payment.installment]).get()
        } else {
            return CreditCardTransactionOrigin.query([payment: payment]).get()
        }
    }

    private Integer countSameCardTransactions(Payment payment) {
        String creditCardHash = payment.billingInfo?.creditCardInfo?.buildCardHash()
        Integer countOfTransactions

        if (creditCardHash) {
            countOfTransactions = paymentAdminService.countAdmin([creditCardHash: creditCardHash])
        }

        return countOfTransactions
    }

    private String getPaymentCreateRemoteIp(Payment payment) {
        CreditCardTransactionOrigin creditCardTransactionOrigin = getCreditCardTransactionOrigin(payment)
        def domainInstance

        if (creditCardTransactionOrigin.originInterface.isInvoice()) {
            domainInstance = payment
        } else if (creditCardTransactionOrigin.originInterface.isInvoicePaymentCampaign()) {
            domainInstance = PaymentCampaign.getPaymentCampaignFromPayment(payment)
        }

        if (!domainInstance) return null

        if (domainInstance instanceof Payment) {
            return PaymentOriginRequesterInfo.query([column: "remoteIp", paymentId: domainInstance.id]).get()
        }

        return AuditLogEvent.query([column: "remoteIp", eventName: "INSERT", className: domainInstance.class.simpleName, persistedObjectId: domainInstance.id.toString()]).get()
    }

    private List<CreditCardTransactionAnalysis> getCreditCardTransactionAnalysis(Payment payment) {
        Map params = [:]

        if (payment.installment) {
            params = [installment: payment.installment]
        } else {
            params = [payment: payment]
        }

        return CreditCardTransactionAnalysis.query(params).list()
    }
}
