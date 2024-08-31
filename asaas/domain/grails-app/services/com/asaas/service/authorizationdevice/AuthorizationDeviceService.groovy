package com.asaas.service.authorizationdevice

import com.asaas.authorizationdevice.AuthorizationDeviceStatus
import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.authorizationdevice.AuthorizationDeviceValidator
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class AuthorizationDeviceService {

    def asaasSegmentioService
    def connectedAccountInfoHandlerService
    def criticalActionConfigService
    def crypterService
    def trustedUserKnownDeviceScheduleService
    def userMultiFactorDeviceService

    public AuthorizationDevice setAsActive(AuthorizationDevice authorizationDevice, UserDeviceSecurityVO userDeviceSecurityVO) {
        BusinessValidation businessValidation = canSetAsActive(authorizationDevice.customer, userDeviceSecurityVO)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        authorizationDevice.status = AuthorizationDeviceStatus.ACTIVE
        authorizationDevice.save(flush: true, failOnError: true)
        saveTrustedUserKnownDeviceScheduleIfPossible(authorizationDevice)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(authorizationDevice))

        return authorizationDevice
    }

    public AuthorizationDevice updateCurrentDevice(Customer customer, AuthorizationDevice newDevice, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: customer]).get()

        oldDevice.deleted = true
        oldDevice.save(failOnError: true)

        setAsActive(newDevice, userDeviceSecurityVO)

        setAuthorizationDeviceToExistingCriticalActionGroups(newDevice)
        userMultiFactorDeviceService.updateUserMultiFactorDevicesIfNecessary(newDevice)

        return newDevice
    }

    public AuthorizationDevice activate(Customer customer, AuthorizationDeviceType authorizationDeviceType, String activationToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice pendingDevice = AuthorizationDevice.pending([customer: customer, type: authorizationDeviceType, locked: false]).get()

        if (!pendingDevice) throw new BusinessException("Não há nenhum dispositivo pendente para autorizar.")

        AuthorizationDevice activeDevice = AuthorizationDevice.active([customer: customer]).get()

        pendingDevice.activationAttempts++

        AuthorizationDeviceValidator authorizationDeviceValidator

        if (pendingDevice.type.isMobileAppToken()) {
            if (activeDevice.type.isSmsToken()) {
                authorizationDeviceValidator = AuthorizationDeviceValidator.getInstance(pendingDevice, AuthorizationDeviceType.SMS_TOKEN)
            } else {
                authorizationDeviceValidator = AuthorizationDeviceValidator.getInstance(activeDevice, AuthorizationDeviceType.MOBILE_APP_TOKEN)
            }
        } else {
            authorizationDeviceValidator = AuthorizationDeviceValidator.getInstance(pendingDevice)
        }

        Boolean isValidToken = authorizationDeviceValidator.validate(activationToken)

        if (isValidToken) {
            setAsActive(pendingDevice, userDeviceSecurityVO)
            setAuthorizationDeviceToExistingCriticalActionGroups(pendingDevice)
            userMultiFactorDeviceService.updateUserMultiFactorDevicesIfNecessary(pendingDevice)

            asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Ativação confirmada", [providerEmail: customer.email, deviceToken: activationToken])
        } else {
             if (pendingDevice.isMaxActivationAttemptsExceeded()) {
                 pendingDevice.setAsLocked()
                 asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Ativação bloqueada", [providerEmail: customer.email, deviceToken: activationToken])
            }

            asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Ativação falhada", [providerEmail: customer.email, deviceToken: activationToken])
        }

        pendingDevice.save(flush: true, failOnError: true)

        return pendingDevice
    }

    public void deleteAllIfNecessary(Long customerId) {
        AuthorizationDevice.query([customerId: customerId]).list().each { device ->
            if (device.deleted) return

            device.deleted = true
            device.save(failOnError: true)
        }
    }

    public AuthorizationDevice save(Customer customer, AuthorizationDeviceType authorizationDeviceType) {
        AuthorizationDevice authorizationDevice = new AuthorizationDevice()

        authorizationDevice.customer = customer
        authorizationDevice.type = authorizationDeviceType

        authorizationDevice.save(flush: true, failOnError: true)

        setEncryptedActivationToken(authorizationDevice)

        return authorizationDevice
    }

    public void setAuthorizationDeviceToExistingCriticalActionGroups(AuthorizationDevice authorizationDevice) {
        CriticalActionGroup.awaitingAuthorization([customer: authorizationDevice.customer]).list().each { CriticalActionGroup criticalActionGroup ->
            criticalActionGroup.authorizationDevice = authorizationDevice
            criticalActionGroup.authorizationAttempts = 0

            if (authorizationDevice.type.isSmsToken()) {
                String token = authorizationDevice.buildToken()
                criticalActionGroup.authorizationToken = crypterService.encryptDomainProperty(criticalActionGroup, "authorizationToken", token)
            } else {
                criticalActionGroup.authorizationToken = null
            }

            criticalActionGroup.save(failOnError: true)
        }
    }

    private void saveTrustedUserKnownDeviceScheduleIfPossible(AuthorizationDevice authorizationDevice) {
        final Integer scheduleDaysAhead = 1
        if (!authorizationDevice.type.isMobileAppToken()) return
        if (!authorizationDevice.userKnownDeviceOrigin) return

        trustedUserKnownDeviceScheduleService.saveIfNecessary(authorizationDevice.userKnownDeviceOrigin, scheduleDaysAhead)
    }

    private BusinessValidation canSetAsActive(Customer customer, UserDeviceSecurityVO userDeviceSecurityVO) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!userDeviceSecurityVO.isTrustable()) {
            asaasSegmentioService.track(userDeviceSecurityVO.userKnownDevice.user.customerId, "device_trustable_validation", [process: "authorization_device_activation"])
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        if (!criticalActionConfigService.isAllowedToUpdateConfig(customer)) {
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        return businessValidation
    }

    private void setEncryptedActivationToken(AuthorizationDevice authorizationDevice) {
        String activationToken = authorizationDevice.buildToken()
        authorizationDevice.activationToken = crypterService.encryptDomainProperty(authorizationDevice, "activationToken", activationToken)

        authorizationDevice.save(flush: true, failOnError: true)
    }
}
