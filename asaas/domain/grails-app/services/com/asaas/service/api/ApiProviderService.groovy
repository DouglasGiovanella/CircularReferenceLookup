package com.asaas.service.api

import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiProviderParser
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiProviderService extends ApiBaseService {

	def customerUpdateRequestService
	def apiResponseBuilderService
    def customerRegisterStatusService
    def customerService

    def find(params) {
        Customer customer = getProviderInstance(params)

        if (!ApiMobileUtils.isMobileAppRequest()) {
            AsaasLogger.info("ApiProviderService.find >>> Endpoint acessado pelo customer [${customer.id}]")
        }

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)
        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(customerUpdateRequest ?: customer))
    }

    def save(params) {
        if (AsaasEnvironment.isSandbox()) return saveForSandbox(params)

        Map fields = ApiProviderParser.parseRequestParams(params)
        Customer customer = getProviderInstance(params)

        AsaasLogger.info("ApiProviderService.save >>> Endpoint acessado pelo customer [${customer.id}]")

        def customerUpdateRequest = customerUpdateRequestService.save(customer.id, fields)

        if (customerUpdateRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerUpdateRequest)
        }

        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(customerUpdateRequest, [buildCriticalAction: true]))
    }

    private Map saveForSandbox(params) {
        Map fields = ApiProviderParser.parseRequestParams(params)

        Customer customer = getProviderInstance(params)
        customer = customerService.updateCommercialInfo(customer.id, fields)

        AsaasLogger.info("ApiProviderService.saveForSandbox >>> Endpoint acessado pelo customer [${customer.id}]")

        if (customer.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customer)
        }

        customerRegisterStatusService.updateAllStatusAsApprovedToSandboxAccount(customer)

        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(customer))
    }
}