package com.asaas.service.facematchcriticalaction

import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.user.UserUpdateRequest
import grails.transaction.Transactional

@Transactional
class CancelFacematchCriticalActionService {

    def facematchCriticalActionService

    public void cancelUserUpdateRequestFacematchIfNecessary(UserUpdateRequest userUpdateRequest) {
        FacematchCriticalAction facematchCriticalActionInProgress = FacematchCriticalAction.inProgress([userUpdateRequest: userUpdateRequest, requester: userUpdateRequest.user]).get()
        if (!facematchCriticalActionInProgress) return

        facematchCriticalActionService.cancel(facematchCriticalActionInProgress)
    }
}
