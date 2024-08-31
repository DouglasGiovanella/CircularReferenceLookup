package com.asaas.service.api.asaaserp

import com.asaas.api.ApiAsaasErrorParser
import com.asaas.api.asaaserp.ApiAsaasErpConsumerInvoiceParser
import com.asaas.consumerinvoice.productinvoice.ConsumerInvoiceProvider
import com.asaas.domain.invoice.ConsumerInvoice
import com.asaas.domain.customer.Customer
import com.asaas.schema.api.v3.asaaserp.AsaasErpConsumerInvoiceSchema
import com.asaas.service.api.ApiBaseService
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ApiAsaasErpConsumerInvoiceService extends ApiBaseService {

    def apiResponseBuilderService
    def groovySchemaService
    def consumerInvoiceService

    public Map save(Map params) {
        List<Map> schemaErrors = groovySchemaService.validate(params, AsaasErpConsumerInvoiceSchema.save)

        if (schemaErrors) return apiResponseBuilderService.buildSchemaValidationErrorList(schemaErrors)

        Customer customer = getProviderInstance(params)
        Map parsedParams = ApiAsaasErpConsumerInvoiceParser.parseRequestParams(params)

        ConsumerInvoice consumerInvoice = consumerInvoiceService.save(customer, ConsumerInvoiceProvider.BASE_ERP, parsedParams)

        if (consumerInvoice.hasErrors()) return apiResponseBuilderService.buildErrorList(consumerInvoice)

        Map responseItem = ApiAsaasErpConsumerInvoiceParser.buildResponseItem(consumerInvoice)

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map canRequest(Map params) {
        Customer customer = getProviderInstance(params)
        BusinessValidation validatedBusiness = consumerInvoiceService.canRequest(customer)

        Map responseMap = [enabled: validatedBusiness.isValid()]
        if (!responseMap.enabled) responseMap.reason = ApiAsaasErrorParser.buildResponseList(validatedBusiness)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }
}
