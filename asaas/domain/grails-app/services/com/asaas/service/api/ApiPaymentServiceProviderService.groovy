package com.asaas.service.api

import com.asaas.api.ApiPaymentServiceProviderParser
import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider

import grails.transaction.Transactional

@Transactional
class ApiPaymentServiceProviderService extends ApiBaseService {

    def apiResponseBuilderService

    public Map show(Map params) {
        Map filters = ApiPaymentServiceProviderParser.parseFilters(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        List<PaymentServiceProvider> paymentServiceProviderList = PaymentServiceProvider.active(filters + [sort: "corporateName", order: "asc"]).list(max: limit, offset: offset, readOnly: true)

        List<Map> paymentServiceProviderListResponse = paymentServiceProviderList.collect { ApiPaymentServiceProviderParser.buildResponseItem(it) }
        return apiResponseBuilderService.buildList(paymentServiceProviderListResponse, limit, offset, paymentServiceProviderList.totalCount)
    }
}
