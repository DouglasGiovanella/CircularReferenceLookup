package com.asaas.service.user

import com.asaas.domain.customer.Customer
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.user.User
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionType
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserFacematchCriticalActionService {

    def userFacematchValidationManagerService
    def userSecurityStageService

    public Boolean canUserCreate(User user, FacematchCriticalActionType facematchCriticalActionType) {
        Map searchParams = [
            exists: true,
            requester: user,
            type: facematchCriticalActionType
        ]
        Boolean userHasFacematchWithSameTypeInProgress = FacematchCriticalAction.inProgress(searchParams).get().asBoolean()

        if (userHasFacematchWithSameTypeInProgress) return false

        return canUserUseFacematch(user)
    }

    public BusinessValidation canBeEnabledForFacematch(User user) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!userSecurityStageService.isLastLevel(user)) {
            businessValidation.addError("userAdmin.canBeEnabledForFacematch.validateUser.isNotInLastUserSecurityLevel")
            return businessValidation
        }

        if (canUserUseFacematch(user)) {
            businessValidation.addError("userAdmin.canBeEnabledForFacematch.validateUser.alreadyEnabledForFacematch")
            return businessValidation
        }

        return businessValidation
    }

    public Boolean canUserUseFacematch(User user) {
        return userFacematchValidationManagerService.canUserUseFacematch(user.id)
    }

    public List<Long> buildUserIdListEnabledForFacematch(List<Long> userIdList) {
        return userFacematchValidationManagerService.buildUserIdListEnabledForFacematch(userIdList)
    }

    public List<String> buildEnabledUsernameAdminList(Customer customer) {
        List<Map> userMapList = User.admin(customer, [columnList: ["id", "username"]]).list()

        List<Long> userIdList = userMapList.collect { Long.valueOf(it.id) }
        List<Long> userIdListEnableForFacematch = buildUserIdListEnabledForFacematch(userIdList)

        if (!userIdListEnableForFacematch) return []

        List<String> enabledUsernameList = []
        for (Map userMap : userMapList) {
            if (userIdListEnableForFacematch.contains(userMap.id)) {
                enabledUsernameList.add(userMap.username)
            }
        }

        return enabledUsernameList
    }
}
