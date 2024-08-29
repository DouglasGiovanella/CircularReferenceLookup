package com.asaas.service.authorizationdevice

import com.asaas.authorizationdevice.AuthorizationDeviceNotificationVO
import com.asaas.authorizationdevice.AuthorizationDeviceStatus
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class MobileAppTokenUpdateRequestService {

    def mobileAppTokenService
    def securityEventNotificationService

    public AuthorizationDevice saveAuthorizationDevice(Customer customer, UserKnownDevice userKnownDeviceOrigin, String deviceModelName, AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        AuthorizationDevice validatedAuthorizationDevice = validateSaveAuthorizationDevice(customer)

        if (validatedAuthorizationDevice.hasErrors()) {
            return validatedAuthorizationDevice
        }

        AuthorizationDevice authorizationDevice = mobileAppTokenService.save(customer, userKnownDeviceOrigin, deviceModelName)

        authorizationDevice.status = AuthorizationDeviceStatus.AWAITING_APPROVAL
        authorizationDevice.save(failOnError: true)

        notifyAboutAuthorizationDeviceUpdateRequestedIfNecessary(authorizationDeviceNotificationVO)

        return authorizationDevice
    }

    private AuthorizationDevice validateSaveAuthorizationDevice(Customer customer) {
        AuthorizationDevice validatedAuthorizationDevice = new AuthorizationDevice()

        if (!AuthorizationDevice.active([customer: customer, exists: true]).get().asBoolean()) {
            DomainUtils.addErrorWithErrorCode(validatedAuthorizationDevice, "active_device_not_found", "Você não possui nenhum dispositivo ativo para solicitar a troca.")
        }

        return validatedAuthorizationDevice
    }

    private void notifyAboutAuthorizationDeviceUpdateRequestedIfNecessary(AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        if (!authorizationDeviceNotificationVO) return
        if (!authorizationDeviceNotificationVO.shouldNotifyUpdateRequested) return
        if (!authorizationDeviceNotificationVO.currentUser) return

        securityEventNotificationService.notifyAndSaveHistoryAboutAuthorizationDeviceUpdateRequested(authorizationDeviceNotificationVO.currentUser)
    }
}
