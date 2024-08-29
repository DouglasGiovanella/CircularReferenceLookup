package com.asaas.service.api

import com.asaas.api.ApiPaymentCampaignParser
import com.asaas.api.paymentCampaign.ApiPaymentCampaignSummaryBuilder
import com.asaas.api.ApiBaseParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.paymentcampaign.PaymentCampaignItem

import grails.transaction.Transactional

@Transactional
class ApiPaymentCampaignService extends ApiBaseService {

    def asaasSegmentioService
    def apiResponseBuilderService
    def paymentCampaignService

    public Map find(Map params) {
        PaymentCampaign paymentCampaign = PaymentCampaign.find(getProviderInstance(params), params.id)
        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignParser.buildResponseItem(paymentCampaign))
    }

    public Map list(Map params) {
        Map fields = ApiPaymentCampaignParser.parseFilters(params)

        List<PaymentCampaign> campaignsList = paymentCampaignService.list(getProviderInstance(params), getLimit(params), getOffset(params), fields)
        List<Map> campaigns = campaignsList.collect { ApiPaymentCampaignParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(campaigns, getLimit(params), getOffset(params), campaignsList.totalCount)
    }

    public Map save(Map params) {
        Map fields = ApiPaymentCampaignParser.parseRequestParams(params)
        Customer customer = getProviderInstance(params)

        PaymentCampaign paymentCampaign = paymentCampaignService.save(customer, fields)

        if (paymentCampaign.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentCampaign)
        }

        asaasSegmentioService.track(customer.id, "payment_link_creation_origin", [domain_id: paymentCampaign.id, origin: ApiBaseParser.getRequestOrigin().toString()])

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignParser.buildResponseItem(paymentCampaign))
    }

    public Map update(Map params) {
        Map fields = ApiPaymentCampaignParser.parseRequestParams(params)

        PaymentCampaign paymentCampaign = paymentCampaignService.update(PaymentCampaign.find(getProviderInstance(params), params.id), fields)

        if (paymentCampaign.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(paymentCampaign)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignParser.buildResponseItem(paymentCampaign))
    }

    public Map summary(Map params) {
        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignSummaryBuilder.build(getProviderInstance(params), params))
    }

    public Map delete(Map params) {
        PaymentCampaign paymentCampaign = paymentCampaignService.delete(PaymentCampaign.find(getProviderInstance(params), params.id))

        return apiResponseBuilderService.buildDeleted(paymentCampaign.publicId)
    }

    public Map restore(Map params) {
        PaymentCampaign paymentCampaign = paymentCampaignService.restore(PaymentCampaign.find(getProviderInstance(params), params.id))

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignParser.buildResponseItem(paymentCampaign))
    }

    public Map toggleActive(Map params) {
        PaymentCampaign paymentCampaign = paymentCampaignService.toggleActive(PaymentCampaign.find(getProviderInstance(params), params.id))

        return apiResponseBuilderService.buildSuccess(ApiPaymentCampaignParser.buildResponseItem(paymentCampaign))
    }

    public Map getCampaignItems(Map params) {
        Map parsedFilters = ApiPaymentCampaignParser.parseItemsFilter(params)
        PaymentCampaign paymentCampaign = PaymentCampaign.find(getProviderInstance(params), params.id)

        List<PaymentCampaignItem> paymentCampaignItemList = PaymentCampaignItem.query(parsedFilters + [paymentCampaign: paymentCampaign]).list(max: getLimit(params), offset: getOffset(params))
        List<Map> paymentCampaignItems = paymentCampaignItemList.collect { ApiPaymentCampaignParser.buildResponseCampaignItem(it) }

        return apiResponseBuilderService.buildList(paymentCampaignItems, getLimit(params), getOffset(params), paymentCampaignItemList.totalCount)
    }

}
