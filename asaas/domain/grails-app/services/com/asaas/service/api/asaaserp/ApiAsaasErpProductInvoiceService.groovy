package com.asaas.service.api.asaaserp

import com.asaas.api.asaaserp.ApiAsaasErpProductInvoiceParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.ProductInvoice
import com.asaas.productinvoice.ProductInvoiceProvider
import com.asaas.schema.api.v3.asaaserp.AsaasErpProductInvoiceSchema
import com.asaas.service.api.ApiBaseService
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ApiAsaasErpProductInvoiceService extends ApiBaseService {

    def apiResponseBuilderService
    def groovySchemaService
    def productInvoiceService

    public Map save(Map params) {
        List<Map> schemaErrors = groovySchemaService.validate(params, AsaasErpProductInvoiceSchema.save)
        if (schemaErrors) return apiResponseBuilderService.buildSchemaValidationErrorList(schemaErrors)

        Map parsedParams = ApiAsaasErpProductInvoiceParser.parseRequestParams(params)
        ProductInvoice productInvoice = productInvoiceService.save(getProviderInstance(params), ProductInvoiceProvider.BASE_ERP, parsedParams)
        if (productInvoice.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(productInvoice)
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasErpProductInvoiceParser.buildResponseItem(productInvoice))
    }

    public Map canRequest(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseMap = [:]
        responseMap.enabled = true

        BusinessValidation validatedBusiness = productInvoiceService.canRequest(customer)
        if (!validatedBusiness.isValid()) {
            responseMap.reason = validatedBusiness.getFirstErrorMessage()
            responseMap.enabled = false
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }
}
