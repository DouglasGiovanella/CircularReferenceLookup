package com.asaas.service.api

import com.asaas.api.ApiPaymentCampaignFileParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.paymentcampaign.PaymentCampaignFile
import com.asaas.domain.file.AsaasFile

import grails.transaction.Transactional

@Transactional
class ApiPaymentCampaignFileService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentCampaignFileService

    public Map find(Map params) {
        PaymentCampaignFile paymentCampaignFile = PaymentCampaignFile.find(getProviderInstance(params), params.id)
        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignFileParser.buildResponseItem(paymentCampaignFile))
    }

    public Map download(Map params) {
        Customer customer = getProviderInstance(params)

        PaymentCampaignFile paymentCampaignFile = PaymentCampaignFile.query([paymentCampaignPublicId: params.paymentCampaignPublicId, publicId: params.id, customer: customer]).get()

        if (!paymentCampaignFile) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        AsaasFile asaasFile = paymentCampaignFile.picture.file
        byte[] bytes = asaasFile.getFileBytes()

        return apiResponseBuilderService.buildFile(bytes, "PaymentCampaignFile_${params.id}.png")
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiPaymentCampaignFileParser.parseRequestParams(params)

        PaymentCampaign paymentCampaign = PaymentCampaign.find(customer, fields.paymentCampaignId)

        List<PaymentCampaignFile> campaignFilesList = PaymentCampaignFile.query([customer: customer, paymentCampaign: paymentCampaign]).list(max: getLimit(params), offset: getOffset(params))
        List<Map> buildedCampaignFilesList = campaignFilesList.collect { ApiPaymentCampaignFileParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(buildedCampaignFilesList, getLimit(params), getOffset(params), campaignFilesList.totalCount)
    }

    public Map save(Map params) {
        Map fields = ApiPaymentCampaignFileParser.parseRequestParams(params)

        PaymentCampaign paymentCampaign = PaymentCampaign.find(getProviderInstance(params), fields.paymentCampaignId)
        PaymentCampaignFile paymentCampaignFile = paymentCampaignFileService.save(paymentCampaign, fields)

        if (paymentCampaignFile.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentCampaignFile)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignFileParser.buildResponseItem(paymentCampaignFile))
    }

    public Map setAsMainFile(Map params) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiPaymentCampaignFileParser.parseRequestParams(params)

        PaymentCampaign paymentCampaign = PaymentCampaign.find(customer, fields.paymentCampaignId)
        PaymentCampaignFile paymentCampaignFile = paymentCampaignFileService.setAsMainFile(customer, paymentCampaign, params.id)

        if (paymentCampaignFile.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentCampaignFile)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignFileParser.buildResponseItem(paymentCampaignFile))
    }

    public Map delete(Map params) {
        PaymentCampaignFile paymentCampaignFile = paymentCampaignFileService.delete(getProviderInstance(params), params.id)

        return apiResponseBuilderService.buildDeleted(paymentCampaignFile.publicId)
    }
}
