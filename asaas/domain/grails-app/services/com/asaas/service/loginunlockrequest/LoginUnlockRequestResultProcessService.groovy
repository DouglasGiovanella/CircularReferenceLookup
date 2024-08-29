package com.asaas.service.loginunlockrequest

import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.loginunlockrequest.LoginUnlockRequestStatus
import grails.transaction.Transactional

@Transactional
class LoginUnlockRequestResultProcessService {

    def customerAlertNotificationService

    public void onFacematchCriticalActionAuthorization(FacematchCriticalAction facematchCriticalAction) {
        LoginUnlockRequest loginUnlockRequest = facematchCriticalAction.loginUnlockRequest
        loginUnlockRequest.status = LoginUnlockRequestStatus.AWAITING_AUTHENTICATION

        loginUnlockRequest.save(failOnError: true)
        customerAlertNotificationService.notifyFacematchLoginUnlockApproved(facematchCriticalAction)
    }

    public void onFacematchCriticalActionManualAnalysisRequired(FacematchCriticalAction facematchCriticalAction) {
        LoginUnlockRequest loginUnlockRequest = facematchCriticalAction.loginUnlockRequest
        loginUnlockRequest.status = LoginUnlockRequestStatus.MANUAL_ANALYSIS_REQUIRED

        loginUnlockRequest.save(failOnError: true)
    }

    public void onFacematchCriticalActionRejection(FacematchCriticalAction facematchCriticalAction) {
        LoginUnlockRequest loginUnlockRequest = facematchCriticalAction.loginUnlockRequest
        loginUnlockRequest.status = LoginUnlockRequestStatus.PENDING

        loginUnlockRequest.save(failOnError: true)
    }

    public void onFacematchCriticalActionCreation(FacematchCriticalAction facematchCriticalAction) {
        LoginUnlockRequest loginUnlockRequest = facematchCriticalAction.loginUnlockRequest
        loginUnlockRequest.status = LoginUnlockRequestStatus.AWAITING_AUTHORIZATION

        loginUnlockRequest.save(failOnError: true)
    }

    public void onAuthentication(LoginUnlockRequest loginUnlockRequest) {
        loginUnlockRequest.status = LoginUnlockRequestStatus.FINISHED

        loginUnlockRequest.save(failOnError: true)
    }
}
