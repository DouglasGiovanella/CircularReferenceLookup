package com.asaas.service.api

import com.asaas.api.ApiInvoiceConfigParser
import com.asaas.customer.CustomerInvoiceConfigStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiInvoiceConfigService extends ApiBaseService {

	def apiResponseBuilderService
	def customerInvoiceConfigService
    def customerInvoiceConfigAdminService

	def find(params) {
		Customer customer = getProviderInstance(params)
		return ApiInvoiceConfigParser.buildResponseItem(customer, CustomerInvoiceConfig.findLatest(customer))
	}

    public Map getLogo(Map params) {
        Customer customer = getProviderInstance(params)
        CustomerInvoiceConfig invoiceConfig = CustomerInvoiceConfig.findLatest(customer)

        byte[] logoBytes = invoiceConfig?.readLogoBytes()
        if (!logoBytes) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildFile(logoBytes, "Logo_${params.id}.png")
    }

	def save(params) {
		Customer customer = getProviderInstance(params)

		Map temporaryLogoFileMap = [:]

		if (params.logoFile) {
			temporaryLogoFileMap = customerInvoiceConfigService.saveTemporaryLogoFile(customer, params)

			if (!temporaryLogoFileMap.success) {
				return apiResponseBuilderService.buildErrorFrom("unknow.error", temporaryLogoFileMap.message)
			}
		}

		Map fields = ApiInvoiceConfigParser.parseRequestParams(params, temporaryLogoFileMap)

        if (!fields.containsKey("providerInfoOnTop")) {
            Boolean hasApprovedInvoiceConfig = CustomerInvoiceConfig.query([exists: true, customer: customer, status: CustomerInvoiceConfigStatus.APPROVED]).get().asBoolean()
            if (!hasApprovedInvoiceConfig) fields.providerInfoOnTop = true
        }

		CustomerInvoiceConfig customerInvoiceConfig = customerInvoiceConfigService.save(customer, fields)
		if (customerInvoiceConfig.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(customerInvoiceConfig)
		}

        if (AsaasEnvironment.isSandbox() && customerInvoiceConfig.isAwaitingApproval()) {
            String observations = Utils.getMessageProperty("system.automaticApproval.description")
            customerInvoiceConfigAdminService.approve(customerInvoiceConfig, observations, true)
        }

		return ApiInvoiceConfigParser.buildResponseItem(customer, customerInvoiceConfig)
	}
}
