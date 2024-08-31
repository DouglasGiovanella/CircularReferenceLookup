package com.asaas.service.api

import com.asaas.api.ApiMobilePhoneRechargeParser
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.integration.celcoin.adapter.mobilephonerecharge.providers.ProviderAdapter
import com.asaas.mobilephonerecharge.MobilePhoneRechargeRepository
import grails.transaction.Transactional

@Transactional
class ApiMobilePhoneRechargeService extends ApiBaseService {

    def apiResponseBuilderService
    def mobilePhoneRechargeService

    public Map find(Map params) {
        MobilePhoneRecharge mobilePhoneRecharge = MobilePhoneRechargeRepository.findReadOnly(getProvider(params), params.id)

        return apiResponseBuilderService.buildSuccess(ApiMobilePhoneRechargeParser.buildResponseItem(mobilePhoneRecharge))
    }

    public Map list(Map params) {
        Map parsedFilters = ApiMobilePhoneRechargeParser.parseFilters(params)

        Map queryParams = parsedFilters
        queryParams.customerId = getProvider(params)

        List<MobilePhoneRecharge> mobilePhoneRechargeList = MobilePhoneRechargeRepository.query(queryParams).readOnly().list(max: getLimit(params), offset: getOffset(params))
        List<Map> mobilePhoneRecharges = mobilePhoneRechargeList.collect { ApiMobilePhoneRechargeParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(mobilePhoneRecharges, getLimit(params), getOffset(params), mobilePhoneRechargeList.totalCount)
    }

    public Map save(Map params) {
        Map parsedParams = ApiMobilePhoneRechargeParser.parseSaveParams(params)

        MobilePhoneRecharge mobilePhoneRecharge = mobilePhoneRechargeService.save(getProviderInstance(params), parsedParams)

        return apiResponseBuilderService.buildSuccess(ApiMobilePhoneRechargeParser.buildResponseItem(mobilePhoneRecharge))
    }

    public Map cancel(Map params) {
        MobilePhoneRecharge mobilePhoneRecharge = MobilePhoneRechargeRepository.find(getProvider(params), params.id)

        mobilePhoneRecharge = mobilePhoneRechargeService.cancel(mobilePhoneRecharge)

        return apiResponseBuilderService.buildSuccess(ApiMobilePhoneRechargeParser.buildResponseItem(mobilePhoneRecharge))
    }

    public Map findProvider(Map params) {
        ProviderAdapter providerAdapter = mobilePhoneRechargeService.findProvider(params)

        return apiResponseBuilderService.buildSuccess(ApiMobilePhoneRechargeParser.buildProviderResponseItem(providerAdapter))
    }
}
