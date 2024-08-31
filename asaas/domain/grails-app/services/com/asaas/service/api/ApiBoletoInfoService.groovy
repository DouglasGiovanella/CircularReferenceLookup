package com.asaas.service.api

import com.asaas.api.ApiBoletoInfoParser
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiBoletoInfoService extends ApiBaseService {

    def boletoChangeInfoRequestService
    def apiResponseBuilderService
    def boletoInfoService

    def save(params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiBoletoInfoParser.parseRequestParams(params)
        Map responseMap = boletoInfoService.update(customer, fields)

        if (!responseMap.success) {
            if (responseMap.inconsistencyObject && responseMap.inconsistencyObject.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(responseMap.inconsistencyObject)
            }

            return apiResponseBuilderService.buildErrorFrom(responseMap.messageCode, Utils.getMessageProperty(responseMap.messageCode))
        }

        def boletoInfo = boletoChangeInfoRequestService.findLatestFromCustomer(customer) ?: boletoInfoService.findFromCustomerOrReturnDefault(customer)
        return apiResponseBuilderService.buildSuccess(ApiBoletoInfoParser.buildResponseItem(customer, boletoInfo))
    }

    def find(params) {
        Customer customer = getProviderInstance(params)
        def boletoInfo = boletoChangeInfoRequestService.findLatestIfIsPendingOrDenied(customer) ?: boletoInfoService.findFromCustomerOrReturnDefault(customer)
        return apiResponseBuilderService.buildSuccess(ApiBoletoInfoParser.buildResponseItem(customer, boletoInfo))
    }
}
