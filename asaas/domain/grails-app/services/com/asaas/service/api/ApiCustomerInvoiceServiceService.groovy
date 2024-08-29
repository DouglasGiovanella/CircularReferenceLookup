package com.asaas.service.api

import com.asaas.api.ApiCustomerInvoiceServiceParser
import com.asaas.api.ApiMobileUtils
import com.asaas.customer.CustomerProductVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerProduct

import grails.transaction.Transactional

@Transactional
class ApiCustomerInvoiceServiceService extends ApiBaseService {

    def apiResponseBuilderService
    def customerMunicipalFiscalOptionsService
    def customerProductService

    def find(params) {
        CustomerProduct customerProduct = CustomerProduct.find(params.id, getProvider(params))
        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceServiceParser.buildResponseItem(customerProduct))
    }

    def list(params) {
        Customer customer = getProviderInstance(params)

        Map filterParams = ApiCustomerInvoiceServiceParser.parseFilters(params)
        List<CustomerProduct> customerProducts = CustomerProduct.query(filterParams + [customer: customer]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> buildedProducts = customerProducts.collect { ApiCustomerInvoiceServiceParser.buildResponseItem(it) }

        List<Map> extraData = []
        if (ApiMobileUtils.isMobileAppRequest() && getOffset(params) == 0) {

            extraData << [isMunicipalServiceCodeEnabled: customerMunicipalFiscalOptionsService.isMunicipalServiceCodeEnabled(customer)]
        }

        return apiResponseBuilderService.buildList(buildedProducts, getLimit(params), getOffset(params), customerProducts.totalCount, extraData)
    }

    def save(params) {
        Map fields = ApiCustomerInvoiceServiceParser.parseRequestParams(params)

        CustomerProduct customerProduct = customerProductService.saveOrUpdate(fields.id, getProviderInstance(params), CustomerProductVO.build(fields))

        if (customerProduct.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerProduct)
        }

        return apiResponseBuilderService.buildSuccess(ApiCustomerInvoiceServiceParser.buildResponseItem(customerProduct))
    }

    def delete(params) {
        customerProductService.delete(CustomerProduct.find(params.id, getProvider(params)))

        return apiResponseBuilderService.buildDeleted(params.id)
    }
}
