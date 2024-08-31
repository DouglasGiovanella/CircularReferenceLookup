package com.asaas.service.api

import com.asaas.domain.userImpersonationRequest.UserImpersonationRequest
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class ApiUserImpersonationRequestService extends ApiBaseService {

    def apiResponseBuilderService
    def userImpersonationRequestService

    public Map authorize(Map params) {
        Customer customer = getProviderInstance(params)

        UserImpersonationRequest userImpersonationRequest = userImpersonationRequestService.authorize(params.long("id"), customer)
        if (userImpersonationRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(userImpersonationRequest)
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }
}
