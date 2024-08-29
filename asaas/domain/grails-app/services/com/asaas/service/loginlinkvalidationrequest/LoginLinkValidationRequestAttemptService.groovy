package com.asaas.service.loginlinkvalidationrequest

import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequest
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequestAttempt

import grails.transaction.Transactional

@Transactional
class LoginLinkValidationRequestAttemptService {

    def auditableMailEventService

    public LoginLinkValidationRequestAttempt create(LoginLinkValidationRequest loginLinkValidationRequest, Boolean isLinkValid, Boolean isRulesValid, Map params) {
        auditableMailEventService.saveLoginLinkClickEventIfPossible(loginLinkValidationRequest, params)

        LoginLinkValidationRequestAttempt loginLinkValidationRequestAttempt = new LoginLinkValidationRequestAttempt()
        loginLinkValidationRequestAttempt.loginLinkValidationRequest = loginLinkValidationRequest
        loginLinkValidationRequestAttempt.linkValid = isLinkValid
        loginLinkValidationRequestAttempt.rulesValid = isRulesValid

        loginLinkValidationRequestAttempt.deviceFingerprint = params.deviceFingerprint
        loginLinkValidationRequestAttempt.remoteIp = params.remoteIp
        loginLinkValidationRequestAttempt.userAgent = params.userAgent
        loginLinkValidationRequestAttempt.browserId = params.browserId
        loginLinkValidationRequestAttempt.latitude = params.latitude
        loginLinkValidationRequestAttempt.longitude = params.longitude
        loginLinkValidationRequestAttempt.city = params.ipLocationAdapter.city
        loginLinkValidationRequestAttempt.state = params.ipLocationAdapter.state
        loginLinkValidationRequestAttempt.country = params.ipLocationAdapter.country

        loginLinkValidationRequestAttempt.save(failOnError: true)

        return loginLinkValidationRequestAttempt
    }
}
