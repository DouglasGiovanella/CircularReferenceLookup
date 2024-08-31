package com.asaas.service.login

import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorDevice
import com.asaas.googleAuthenticator.GoogleAuthenticatorManager
import com.asaas.login.MfaType
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class UserMultiFactorDeviceService {

    def crypterService

    public void registerSMS(User user, String phoneNumber) {
        registerSMS(user, phoneNumber, false)
    }

    public void registerSMS(User user, String phoneNumber, Boolean flushOnSave) {
        deleteOldDevices(user)

        UserMultiFactorDevice userMultiFactorDevice = new UserMultiFactorDevice()
        userMultiFactorDevice.user = user
        userMultiFactorDevice.mfaType = MfaType.SMS
        userMultiFactorDevice.mobilePhone = phoneNumber
        userMultiFactorDevice.save(failOnError: true, flush: flushOnSave)

        enableUserMfa(user)
    }

    public void registerEmail(User user) {
        registerEmail(user, false)
    }

    public void registerEmail(User user, Boolean flushOnSave) {
        deleteOldDevices(user)

        UserMultiFactorDevice userMultiFactorDevice = new UserMultiFactorDevice()
        userMultiFactorDevice.user = user
        userMultiFactorDevice.mfaType = MfaType.EMAIL
        userMultiFactorDevice.save(failOnError: true, flush: flushOnSave)

        enableUserMfa(user)
    }

    public UserMultiFactorDevice registerTotp(User user, String mfaCode, String mfaToken) {
        UserMultiFactorDevice userMultiFactorDevice = new UserMultiFactorDevice()

        if (!mfaCode || !GoogleAuthenticatorManager.verifyCode(mfaToken, mfaCode?.toInteger())) {
            DomainUtils.addError(userMultiFactorDevice, "Código inválido")
            return userMultiFactorDevice
        }

        deleteOldDevices(user)

        userMultiFactorDevice.user = user
        userMultiFactorDevice.mfaType = MfaType.TOTP
        userMultiFactorDevice.save(failOnError: true)

        String encryptedTotpSharedKey = crypterService.encryptDomainProperty(userMultiFactorDevice, "totpSharedKey", mfaToken)
        userMultiFactorDevice.totpSharedKey = encryptedTotpSharedKey
        userMultiFactorDevice.save(failOnError: true)

        enableUserMfa(user)

        return userMultiFactorDevice
    }

    public void updateUserMultiFactorDevicesIfNecessary(AuthorizationDevice authorizationDevice) {
        if (!authorizationDevice.type.isSmsToken()) return

        List<UserMultiFactorDevice> userMultiFactorDeviceSmsList = UserMultiFactorDevice.query(['user[in]': authorizationDevice.customer.getUsers(), mfaType: MfaType.SMS]).list()

        for (UserMultiFactorDevice device : userMultiFactorDeviceSmsList) {
            if (device.user.mobilePhone) continue

            device.mobilePhone = authorizationDevice.phoneNumber
            device.save(failOnError: true)
        }
    }

    private void deleteOldDevices(User user) {
        List<UserMultiFactorDevice> oldUserMultiFactorDeviceList = UserMultiFactorDevice.query([user: user]).list()
        for (UserMultiFactorDevice device : oldUserMultiFactorDeviceList) {
            device.deleted = true
            device.save(failOnError: true)
        }
    }

    private void enableUserMfa(User user) {
        user.mfaEnabled = true
        user.mfaEnablingDeadline = null
        user.save(failOnError: true)
    }
}
