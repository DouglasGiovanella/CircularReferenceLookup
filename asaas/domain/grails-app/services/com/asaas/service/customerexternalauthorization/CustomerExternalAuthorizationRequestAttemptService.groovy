package com.asaas.service.customerexternalauthorization

import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestAttempt

import grails.transaction.Transactional

@Transactional
class CustomerExternalAuthorizationRequestAttemptService {

    public CustomerExternalAuthorizationRequestAttempt save(Long externalAuthorizationRequestId, Integer status, String requestData, String responseData, String url, String errorMessage) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = CustomerExternalAuthorizationRequest.read(externalAuthorizationRequestId)

        CustomerExternalAuthorizationRequestAttempt externalAuthorizationAttempt = new CustomerExternalAuthorizationRequestAttempt()
        externalAuthorizationAttempt.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationAttempt.status = status
        externalAuthorizationAttempt.requestData = requestData
        externalAuthorizationAttempt.responseData = responseData
        externalAuthorizationAttempt.url = url
        externalAuthorizationAttempt.errorMessage = errorMessage
        return externalAuthorizationAttempt.save(failOnError: true)
    }

}
