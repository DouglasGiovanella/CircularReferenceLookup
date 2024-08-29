package com.asaas.service.authorizationdevice

import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.utils.PhoneNumberUtils
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder

@Transactional
class AuthorizationDeviceAdminService {

    def adminAccessTrackingService
    def authorizationDeviceService
    def criticalActionConfigService
    def customerInteractionService
    def smsTokenService

    public void resetDevice(Long customerId, String phoneNumber) {
        Customer customer = Customer.get(customerId)
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query(["customerId": customerId]).get()

        if (!customerCriticalActionConfig) criticalActionConfigService.save(customer)

        authorizationDeviceService.deleteAllIfNecessary(customerId)

        smsTokenService.savePending(customer, phoneNumber, null)
    }

    public void resetToNewActiveDevice(Long customerId, String phoneNumber, UserDeviceSecurityVO userDeviceSecurityVO) {
        String sanitizedPhoneNumber = PhoneNumberUtils.sanitizeNumber(phoneNumber)
        if (!PhoneNumberUtils.validateMobilePhone(sanitizedPhoneNumber)) throw new BusinessException("O celular informado é inválido.")

        Customer customer = Customer.read(customerId)

        AuthorizationDevice device = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        if (device?.phoneNumber == sanitizedPhoneNumber) throw new BusinessException("Este número já está ativo como Token SMS")

        resetToActiveDevice(customer, userDeviceSecurityVO, sanitizedPhoneNumber)
    }

    public void resetLockedToNewActiveDevice(UserDeviceSecurityVO userDeviceSecurityVO, Long customerId) {
        Customer customer = Customer.read(customerId)

        AuthorizationDevice authorizationDevice = AuthorizationDevice.active([
            customer: customer,
            type: AuthorizationDeviceType.SMS_TOKEN,
            locked: true]).get()

        if (!authorizationDevice) throw new BusinessException("Dispositivo atual não está bloqueado.")

        resetToActiveDevice(customer, userDeviceSecurityVO, authorizationDevice.phoneNumber)
    }

    private void resetToActiveDevice(Customer customer, UserDeviceSecurityVO userDeviceSecurityVO, String phoneNumber) {
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()
        if (!customerCriticalActionConfig) criticalActionConfigService.save(customer)

        authorizationDeviceService.deleteAllIfNecessary(customer.id)
        smsTokenService.saveActive(customer, phoneNumber, userDeviceSecurityVO)

        customerInteractionService.saveManualResetDeviceInteraction(customer, phoneNumber)
        adminAccessTrackingService.save(RequestContextHolder.requestAttributes.params, customer.id.toString(), customer.id)
    }
}
