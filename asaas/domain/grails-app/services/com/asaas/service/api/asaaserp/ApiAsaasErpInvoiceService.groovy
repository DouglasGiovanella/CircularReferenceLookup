package com.asaas.service.api.asaaserp

import com.asaas.api.asaaserp.ApiAsaasErpInvoiceParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.Invoice
import com.asaas.service.api.ApiBaseService
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ApiAsaasErpInvoiceService extends ApiBaseService {

    def apiResponseBuilderService
    def asaasErpCustomerInvoiceService

    public Map saveAuthorizedInvoice(Map params) {
        Map parsedParams = ApiAsaasErpInvoiceParser.parseRequestParams(params)
        Invoice invoice = asaasErpCustomerInvoiceService.saveAuthorizedInvoice(getProviderInstance(params), parsedParams)

        if (invoice.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(invoice)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasErpInvoiceParser.buildResponseItem(invoice))
    }

    public Map cancelAuthorizedInvoice(Map params) {
        Long customerId = getProvider(params)
        Invoice invoice = asaasErpCustomerInvoiceService.cancelAuthorizedInvoice(customerId, params)

        if (invoice.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(invoice)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasErpInvoiceParser.buildResponseItem(invoice))
    }

    public Map canRequest(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseMap = [:]
        responseMap.enabled = true

        BusinessValidation validatedBusiness = asaasErpCustomerInvoiceService.canRequest(customer)
        if (!validatedBusiness.isValid()) {
            responseMap.reason = validatedBusiness.getFirstErrorMessage()
            responseMap.enabled = false
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }
}
