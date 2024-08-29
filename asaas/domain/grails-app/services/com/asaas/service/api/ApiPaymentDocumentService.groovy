package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiPaymentDocumentParser
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDocument

import grails.transaction.Transactional

@Transactional
class ApiPaymentDocumentService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentDocumentService

    public Map find(Map params) {
        PaymentDocument paymentDocument = PaymentDocument.find(getDocumentId(params), getProvider(params))

        return apiResponseBuilderService.buildSuccess(ApiPaymentDocumentParser.buildResponseItem(paymentDocument))
    }

    public Map list(Map params) {
        Payment payment = Payment.find(params.paymentId, getProvider(params))
        List<PaymentDocument> paymentDocuments = paymentDocumentService.list(payment)

        List<Map> responseItems = paymentDocuments.collect { ApiPaymentDocumentParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), paymentDocuments.size())
    }

    public Map save(Map params) {
        Map fields = ApiPaymentDocumentParser.parseRequestParams(params)

        Payment payment = Payment.find(params.paymentId, getProvider(params))
        PaymentDocument paymentDocument = paymentDocumentService.save(payment, fields)

        if (paymentDocument.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentDocument, [originalErrorCode: true])
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentDocumentParser.buildResponseItem(paymentDocument))
    }

    public Map update(Map params) {
        Map fields = ApiPaymentDocumentParser.parseRequestParams(params)

        PaymentDocument paymentDocument = paymentDocumentService.update(getProviderInstance(params), getDocumentId(params), fields)

        return apiResponseBuilderService.buildSuccess(ApiPaymentDocumentParser.buildResponseItem(paymentDocument))
    }

    public Map delete(Map params) {
        String documentId = getDocumentId(params)

        paymentDocumentService.delete(getProviderInstance(params), documentId)
        return apiResponseBuilderService.buildDeleted(documentId)
    }

    private String getDocumentId(Map params) {
        return params.documentId ?: params.id
    }
}
