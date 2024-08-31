package com.asaas.service.api

import com.asaas.api.ApiCustomerFiscalConfigParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFiscalConfig
import com.asaas.exception.ResourceNotFoundException

import grails.transaction.Transactional

@Transactional
class ApiCustomerFiscalConfigService extends ApiBaseService {

    def apiResponseBuilderService
    def customerFiscalConfigService

    def find(params) {
        Customer customer = getProviderInstance(params)
        CustomerFiscalConfig customerFiscalConfig = CustomerFiscalConfig.query([customerId: customer.id]).get()

        if (!customerFiscalConfig && ApiMobileUtils.isMobileAppRequest()) {
            customerFiscalConfig = new CustomerFiscalConfig()
            customerFiscalConfig.discard()
            customerFiscalConfig.customer = customer
        }

        if (!customerFiscalConfig) {
            throw new ResourceNotFoundException("Configurações gerais da nota fiscal inexistente.")
        }

        return apiResponseBuilderService.buildSuccess(ApiCustomerFiscalConfigParser.buildResponseItem(customerFiscalConfig))
    }

    def save(params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiCustomerFiscalConfigParser.parseRequestParams(params)

        CustomerFiscalConfig customerFiscalConfig = customerFiscalConfigService.save(customer, fields)

        if (customerFiscalConfig.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerFiscalConfig)
        }

        return apiResponseBuilderService.buildSuccess(ApiCustomerFiscalConfigParser.buildResponseItem(customerFiscalConfig))
    }
}
