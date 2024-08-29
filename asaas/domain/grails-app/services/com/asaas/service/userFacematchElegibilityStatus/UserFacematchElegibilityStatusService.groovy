package com.asaas.service.userFacematchElegibilityStatus

import com.asaas.domain.user.User
import com.asaas.domain.user.UserFacematchElegibilityStatus
import grails.transaction.Transactional

@Transactional
class UserFacematchElegibilityStatusService {

    public void update(User user, Boolean attemptedChangeUserCriticalInfo) {
        UserFacematchElegibilityStatus userFacematchElegibilityStatus = UserFacematchElegibilityStatus.query([user: user]).get()

        if (!userFacematchElegibilityStatus) {
            userFacematchElegibilityStatus = new UserFacematchElegibilityStatus()
            userFacematchElegibilityStatus.user = user
        }

        userFacematchElegibilityStatus.attemptedChangeUserCriticalInfo = attemptedChangeUserCriticalInfo
        userFacematchElegibilityStatus.save(failOnError: true)
    }

    public void delete(User user) {
        UserFacematchElegibilityStatus userFacematchElegibilityStatus = UserFacematchElegibilityStatus.query([user: user]).get()
        if (!userFacematchElegibilityStatus) return

        userFacematchElegibilityStatus.deleted = true
        userFacematchElegibilityStatus.save(failOnError: true)
    }
}
