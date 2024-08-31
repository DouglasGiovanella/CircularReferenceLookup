package com.asaas.service.login

import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import grails.transaction.Transactional

@Transactional
class LoginSessionService {

    def crypterService
    def userApiKeyService
    def userKnownDeviceService
    def userMobileDeviceService
    def userTrustedDeviceService
    def userWebAuthenticationService

    public void invalidateCustomerLoginSessions(Customer customer) {
        List<Long> customerUserIdList = User.query([column: "id", customer: customer]).list()
        for (Long userId : customerUserIdList) {
            invalidateUserLoginSessions(userId)
        }
    }

    public void invalidateUserLoginSessions(Long userId) {
        User user = User.get(userId)

        userWebAuthenticationService.invalidateLoginSession(user)
        userMobileDeviceService.invalidateAll(user)
        userApiKeyService.invalidateAll(user, true)
        userTrustedDeviceService.invalidateAll(user)
        !AsaasEnvironment.isJobServer() ? userKnownDeviceService.deactivateAllExceptCurrent(user.id) : userKnownDeviceService.deactivateAll(user.id)
    }

    public void invalidateLoginSession(Long userId, Long userKnownDeviceId) {
        UserKnownDevice userKnownDevice = UserKnownDevice.query([id: userKnownDeviceId, userId: userId]).get()
        if (!userKnownDevice) throw new RuntimeException("UserKnownDevice [${userKnownDeviceId}] não encontrado.")

        userKnownDeviceService.deactivate(userKnownDevice)
        if (userKnownDevice.platform.isWeb() && userKnownDevice.encryptedSessionId) {
            String sessionId = crypterService.decryptDomainProperty(userKnownDevice, "encryptedSessionId", userKnownDevice.encryptedSessionId)
            userWebAuthenticationService.createInvalidatedUserWebSession(userKnownDevice.userId, sessionId)
        } else {
            userApiKeyService.delete(userKnownDevice.userApiKey)
        }
    }
}
