package com.asaas.service.user

import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorAuth
import com.asaas.login.MfaStatus
import com.asaas.login.MfaType
import grails.transaction.Transactional

@Transactional
class UserAdminService {

    public Boolean isUserEmailValid(Long userId) {
        Boolean hasSuccessfulMfaAuthorizationByEmail = UserMultiFactorAuth.query([exists: true, userId: userId, status: MfaStatus.AUTH_SUCCESSED, type: MfaType.EMAIL]).get().asBoolean()
        return hasSuccessfulMfaAuthorizationByEmail
    }

    public Boolean isUserMobilePhoneValid(Long userId) {
        String userMobilePhone = User.query([id: userId, column: "mobilePhone", ignoreCustomer: true]).get()
        if (!userMobilePhone) return false

        Boolean hasSuccessfulMfaAuthorizationBySms = UserMultiFactorAuth.query([exists: true,
                                                                                status: MfaStatus.AUTH_SUCCESSED,
                                                                                userId: userId,
                                                                                type: MfaType.SMS,
                                                                                userMultiFactorDeviceMobilePhone: userMobilePhone]).get().asBoolean()

        return hasSuccessfulMfaAuthorizationBySms
    }
}
