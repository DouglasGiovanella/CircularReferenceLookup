package com.asaas.service.facematchcriticalaction

import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.domain.user.User
import com.asaas.domain.user.UserUpdateRequest
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionStatus
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionType
import grails.transaction.Transactional

@Transactional
class CreateFacematchCriticalActionService {

    def asaasSegmentioService

    public FacematchCriticalAction save(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User requester) {
        FacematchCriticalAction facematchCriticalAction = new FacematchCriticalAction()
        facematchCriticalAction.authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequest
        facematchCriticalAction.type = FacematchCriticalActionType.AUTHORIZATION_DEVICE_UPDATE_REQUEST
        facematchCriticalAction.requester = requester

        facematchCriticalAction.save(failOnError: true)

        trackCreate(requester.customer.id, facematchCriticalAction.id)

        return facematchCriticalAction
    }

    public FacematchCriticalAction saveIfNecessary(LoginUnlockRequest loginUnlockRequest, User requester) {
        FacematchCriticalAction facematchCriticalAction = FacematchCriticalAction.query([loginUnlockRequest: loginUnlockRequest, requester: requester, "status[in]": FacematchCriticalActionStatus.listInProgress()]).get()
        if (facematchCriticalAction) return facematchCriticalAction

        facematchCriticalAction = new FacematchCriticalAction()
        facematchCriticalAction.loginUnlockRequest = loginUnlockRequest
        facematchCriticalAction.type = FacematchCriticalActionType.LOGIN_UNLOCK_REQUEST
        facematchCriticalAction.requester = requester
        facematchCriticalAction.publicId = UUID.randomUUID()

        facematchCriticalAction.save(failOnError: true)

        trackCreate(requester.customer.id, facematchCriticalAction.id)

        return facematchCriticalAction
    }

    public FacematchCriticalAction saveUserUpdateRequestIfNecessary(UserUpdateRequest userUpdateRequest, User requester) {
        FacematchCriticalAction facematchCriticalAction = FacematchCriticalAction.query([userUpdateRequest: userUpdateRequest, requester: requester, "status[in]": FacematchCriticalActionStatus.listInProgress()]).get()
        if (facematchCriticalAction) return facematchCriticalAction

        facematchCriticalAction = new FacematchCriticalAction()
        facematchCriticalAction.userUpdateRequest = userUpdateRequest
        facematchCriticalAction.type = FacematchCriticalActionType.USER_UPDATE_REQUEST
        facematchCriticalAction.requester = requester

        facematchCriticalAction.save(failOnError: true)

        trackCreate(requester.customer.id, facematchCriticalAction.id)

        return facematchCriticalAction
    }

    private void trackCreate(Long customerId, Long facematchCriticalActionId) {
        Map trackInfo = [:]
        trackInfo.action = "create"
        trackInfo.facematchCriticalActionId = facematchCriticalActionId
        asaasSegmentioService.track(customerId, "facematch_critical_action", trackInfo)
    }
}
