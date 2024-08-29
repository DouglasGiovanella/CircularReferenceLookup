package com.asaas.service.api.payment

import com.asaas.api.ApiBaseParser
import com.asaas.api.payment.parser.ApiPaymentLeanParser
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.payment.PaymentFineConfig
import com.asaas.domain.payment.PaymentInterestConfig
import com.asaas.domain.paymentcampaign.PaymentCampaignItem
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.subscriptionpayment.SubscriptionPayment
import com.asaas.service.api.ApiBaseService
import grails.transaction.Transactional

@Transactional
class ApiPaymentLeanService extends ApiBaseService {

    public Map find(String publicPaymentId) {
        Payment payment = Payment.find(publicPaymentId, ApiBaseParser.getProviderId())

        String paymentCampaignPublicId = getPaymentCampaignPublicId(payment)
        String pixTransactionPublicId = getPixTransactionPublicId(payment)
        PaymentDiscountConfig paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: payment.id, readOnly: true]).get()
        PaymentFineConfig paymentFineConfig = PaymentFineConfig.query([paymentId: payment.id, readOnly: true]).get()
        PaymentInterestConfig paymentInterestConfig = PaymentInterestConfig.query([paymentId: payment.id, readOnly: true]).get()
        Boolean isPaymentAnticipable = PaymentAnticipableInfo.findAnticipableByPaymentId(payment.id)

        return ApiPaymentLeanParser.buildResponseItem(payment, pixTransactionPublicId, paymentCampaignPublicId, paymentDiscountConfig, paymentFineConfig, paymentInterestConfig, isPaymentAnticipable)
    }

    private String getPaymentCampaignPublicId(Payment payment) {
        if (payment.installment) return PaymentCampaignItem.query([column: "paymentCampaign.publicId", installment: payment.installment, includeDeleted: true, readOnly: true]).get()

        Long subscriptionId = SubscriptionPayment.query([payment: payment, column: "subscription.id", readOnly: true]).get()
        if (subscriptionId) return PaymentCampaignItem.query([column: "paymentCampaign.publicId", subscriptionId: subscriptionId, includeDeleted: true, readOnly: true]).get()

        return PaymentCampaignItem.query([column: "paymentCampaign.publicId", payment: payment, includeDeleted: true, readOnly: true]).get()
    }

    private String getPixTransactionPublicId(Payment payment) {
        if (payment.billingType.isPix() && payment.status.isReceived()) {
            return PixTransaction.credit([column: "pixTransaction.publicId", payment: payment, readOnly: true]).get()
        }

        return null
    }

}
