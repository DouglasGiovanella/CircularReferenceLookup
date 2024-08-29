package com.asaas.service.user

import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.user.adapter.UserSelfieAdapter
import com.asaas.useradditionalinfo.UserSelfieOrigin
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserSelfieService {

    def userFacematchCriticalActionService
    def userFacematchElegibilityStatusService
    def userSelfieManagerService

    public void enableUserForFacematchIfPossible(User user) {
        BusinessValidation businessValidation = userFacematchCriticalActionService.canBeEnabledForFacematch(user)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        UserSelfieAdapter userSelfieAdapter = new UserSelfieAdapter()
        userSelfieAdapter.origin = UserSelfieOrigin.VALIDATED_USER_ADDITIONAL_INFO

        userSelfieManagerService.updateBestSelfie(user.id, userSelfieAdapter)
        userFacematchElegibilityStatusService.delete(user)
    }
}
