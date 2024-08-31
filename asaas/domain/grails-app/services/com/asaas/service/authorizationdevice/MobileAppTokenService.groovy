package com.asaas.service.authorizationdevice

import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.crypto.RsaCrypter
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.exception.BusinessException
import com.asaas.googleAuthenticator.GoogleAuthenticatorManager
import com.asaas.userdevicesecurity.UserDeviceSecurityVO

import grails.transaction.Transactional

@Transactional
class MobileAppTokenService {

    def authorizationDeviceService
    def crypterService
    def grailsApplication

    public AuthorizationDevice saveActive(Customer customer, String deviceModelName, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice authorizationDevice = save(customer, userDeviceSecurityVO.userKnownDevice, deviceModelName)

        authorizationDevice = authorizationDeviceService.setAsActive(authorizationDevice, userDeviceSecurityVO)

        return authorizationDevice
    }

    public AuthorizationDevice save(Customer customer, String deviceModelName) {
        return save(customer, null, deviceModelName)
    }

    public AuthorizationDevice save(Customer customer, UserKnownDevice userKnownDeviceOrigin, String deviceModelName) {
        AuthorizationDevice pendingDevice = AuthorizationDevice.pending([customer: customer, type: AuthorizationDeviceType.MOBILE_APP_TOKEN]).get()

        if (pendingDevice) {
            pendingDevice.deleted = true
            pendingDevice.save(failOnError: true)
        }

        AuthorizationDevice authorizationDevice = authorizationDeviceService.save(customer, AuthorizationDeviceType.MOBILE_APP_TOKEN)
        authorizationDevice.deviceModelName = deviceModelName
        authorizationDevice.userKnownDeviceOrigin = userKnownDeviceOrigin
        authorizationDevice.secretKey = crypterService.encryptDomainProperty(authorizationDevice, "secretKey", GoogleAuthenticatorManager.createCredentials())
        authorizationDevice.save(failOnError: true)

        return authorizationDevice
    }

    public AuthorizationDevice activate(Customer customer, String activationToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: customer]).get()
        AuthorizationDevice newDevice = authorizationDeviceService.activate(customer, AuthorizationDeviceType.MOBILE_APP_TOKEN, activationToken, userDeviceSecurityVO)

        if (newDevice.locked && !newDevice.status.isActive()) {
            newDevice.deleted = true
        }

        if (oldDevice) {
            if (newDevice.status.isActive()) {
                oldDevice.deleted = true
            }

            if (newDevice.locked) {
                oldDevice.locked = true
            }

            oldDevice.save(failOnError: true)
        }

        newDevice.save(failOnError: true)

        return newDevice
    }

    public String encryptSecretKey(AuthorizationDevice authorizationDevice) {
        String secretKey = crypterService.decryptDomainProperty(authorizationDevice, "secretKey", authorizationDevice.secretKey)

        byte[] moduleBytes = grailsApplication.config.asaas.mobile.api.encryption.authorizationDevice.secretKey.rsa.module.decodeBase64()
        byte[] exponentBytes = grailsApplication.config.asaas.mobile.api.encryption.authorizationDevice.secretKey.rsa.exponent.decodeBase64()

        return RsaCrypter.encrypt(secretKey.decodeBase64(), moduleBytes, exponentBytes)
    }

    public AuthorizationDevice unlock(Customer customer, String activationToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice currentDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.MOBILE_APP_TOKEN]).get()

        if (!currentDevice) {
            throw new BusinessException("É necessário possuir um dispositivo ativo para solicitar o desbloqueio do mesmo.")
        }

        if (!currentDevice.locked) {
            throw new BusinessException("O dispositivo ativo não está bloqueado.")
        }

        AuthorizationDevice newDevice = AuthorizationDevice.pending([customer: customer, secretKey: currentDevice.secretKey]).get()

        if (!newDevice) {
            newDevice = save(customer, currentDevice.deviceModelName)
        }

        activate(customer, activationToken, userDeviceSecurityVO)

        return newDevice
    }
}
