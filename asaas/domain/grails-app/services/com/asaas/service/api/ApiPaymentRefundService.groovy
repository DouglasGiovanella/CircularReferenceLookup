package com.asaas.service.api

import com.asaas.api.ApiPaymentRefundParser
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ApiPaymentRefundService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentRefundService
    def refundRequestService

    public Map list(Map params) {
        Payment refundedPayment = Payment.find(params.id, getProvider(params))

        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map query = [
            columnList: ["id", "dateCreated", "status", "value", "pixTransaction"],
            payment: refundedPayment
        ]

        List<Map> refundList = PaymentRefund.query(query).list(max: limit, offset: offset, readOnly: true)

        List<Map> refundedPayments = ApiPaymentRefundParser.buildRefundListResponseItem(refundList, [:], refundedPayment)

        return ApiResponseBuilder.buildSuccessList(refundedPayments, limit, offset, refundList.totalCount)
    }

    public Map cancel(Map params) {
        Long paymentId = Payment.validateOwnerAndRetrieveId(params.id, getProvider(params))

        PaymentRefund paymentRefundToCancel = PaymentRefund.query([paymentId: paymentId, id: Utils.toLong(params.refundId)]).get()

        if (!paymentRefundToCancel) {
            return ApiResponseBuilder.buildNotFound()
        }

        PaymentRefund paymentRefundCancelled = paymentRefundService.cancel(paymentRefundToCancel)

        return ApiResponseBuilder.buildSuccess(ApiPaymentRefundParser.buildPaymentRefundResponseItem(paymentRefundCancelled, [:], null))
    }

    public Map requestBankSlipRefund(Map params) {
        Payment payment = Payment.find(params.id, getProvider(params))
        Customer customer = getProviderInstance(params)

        if (payment.billingType != BillingType.BOLETO) {
            return apiResponseBuilderService.buildErrorFrom("invalid_action", "Somente é possível solicitar o estorno quando a forma de pagamento for boleto bancário.")
        }

        RefundRequest refundRequest = refundRequestService.save(payment.id, customer)

        Map response = [:]
        response.requestUrl = refundRequestService.generateRefundLink(refundRequest.publicId)

        return apiResponseBuilderService.buildSuccess(response)
    }
}
