package com.asaas.service.usermobiledevice

import com.asaas.domain.user.User
import com.asaas.domain.usermobiledevice.UserMobileDevice
import com.asaas.mobileappidentifier.MobileAppIdentifier
import com.asaas.mobileapplicationtype.MobileApplicationType
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class UserMobileDeviceService {

    public UserMobileDevice save(User user, String token, MobileAppIdentifier platform, MobileApplicationType applicationType) {
        if (!user || !token || !platform) throw new RuntimeException("Algum atributo obrigatório não foi informado.")

        UserMobileDevice userMobileDevice = UserMobileDevice.query([user: user, token: token, order: "asc"]).get()

        if (userMobileDevice) {
            return userMobileDevice
        }

        delete(token)

        userMobileDevice = new UserMobileDevice()
        userMobileDevice.user = user
        userMobileDevice.token = token
        userMobileDevice.platform = platform
        userMobileDevice.applicationType = applicationType
        userMobileDevice.save(flush: true, failOnError: true)

        return userMobileDevice
    }

    public Long delete(String token) {
        Long userMobileDeviceId

        Utils.withNewTransactionAndRollbackOnError({
            UserMobileDevice userMobileDevice = UserMobileDevice.query([token: token, order: "asc"]).get()

            if (userMobileDevice) {
                userMobileDevice.deleted = true
                userMobileDevice.save(failOnError: true)
                userMobileDeviceId = userMobileDevice.id
            }

        }, [logErrorMessage: "Error ao executar o delete do UserMobileDevice [${token}]"])

        return userMobileDeviceId
    }

    public void invalidateAll(User user) {
        UserMobileDevice.executeUpdate("update UserMobileDevice set deleted = true, lastUpdated = :now where user = :user and deleted = false", [now: new Date(), user: user])
    }
}
