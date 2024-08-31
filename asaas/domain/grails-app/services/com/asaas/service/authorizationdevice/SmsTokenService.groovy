package com.asaas.service.authorizationdevice

import com.asaas.authorizationdevice.AuthorizationDeviceNotificationVO
import com.asaas.authorizationdevice.AuthorizationDeviceStatus
import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.authorizationdevice.AuthorizationDeviceVO
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.utils.PhoneNumberUtils
import grails.transaction.Transactional

@Transactional
class SmsTokenService {

    def asaasSegmentioService
    def authorizationDeviceService
    def crypterService
    def smsSenderService
    def securityEventNotificationService
    def userMultiFactorDeviceService

    public AuthorizationDevice savePending(Customer customer, String phoneNumber, AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        AuthorizationDevice authorizationDevice = save(customer, phoneNumber)

        sendActivationToken(authorizationDevice)

        notifyAboutAuthorizationDeviceUpdateRequestedIfNecessary(authorizationDeviceNotificationVO)

        return authorizationDevice
    }

    public AuthorizationDevice saveActive(Customer customer, String phoneNumber, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice authorizationDevice = save(customer, phoneNumber)

        authorizationDevice = authorizationDeviceService.setAsActive(authorizationDevice, userDeviceSecurityVO)

        userMultiFactorDeviceService.updateUserMultiFactorDevicesIfNecessary(authorizationDevice)

        authorizationDeviceService.setAuthorizationDeviceToExistingCriticalActionGroups(authorizationDevice)

        return authorizationDevice
    }

    public AuthorizationDevice save(Customer customer, String phoneNumber) {
        AuthorizationDevice authorizationDevice = authorizationDeviceService.save(customer, AuthorizationDeviceType.SMS_TOKEN)

        authorizationDevice.phoneNumber = Utils.removeNonNumeric(phoneNumber)

        authorizationDevice.save(flush: true, failOnError: true)

        return authorizationDevice
    }

    public void sendActivationToken(AuthorizationDevice authorizationDevice) {
        String smsMessage = "Olá! Este é o seu código de autorização para ativar seu Token SMS: " + authorizationDevice.getDecryptedActivationToken()
        Boolean sent = smsSenderService.send(smsMessage, authorizationDevice.phoneNumber, false, [:])

        if (!sent) {
            throw new BusinessException("O número ${authorizationDevice.phoneNumber} não é um número móvel válido. Verifique-o e tente novamente.")
        }

        authorizationDevice.attemptsToSendToken++
        authorizationDevice.save(failOnError: true)
    }

    public AuthorizationDeviceVO requestUpdateCurrent(Customer customer, String phoneNumber, AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        AuthorizationDevice pendingDevice = updateCurrent(customer, phoneNumber, authorizationDeviceNotificationVO)

        AuthorizationDeviceVO authorizationDeviceVO = new AuthorizationDeviceVO()
        authorizationDeviceVO.messageCode = "authorizationDevice.code.sent"
        authorizationDeviceVO.pendingDevice = pendingDevice

        return authorizationDeviceVO
    }

    public AuthorizationDeviceVO requestCancelUpdateCurrent(Customer customer) {
        cancelUpdateCurrent(customer)

        AuthorizationDeviceVO authorizationDeviceVO = new AuthorizationDeviceVO()
        authorizationDeviceVO.messageCode = "authorizationDevice.requested.main.phone.change.cancelled"

        return authorizationDeviceVO
    }

    public AuthorizationDeviceVO requestAuthorizeUpdateCurrent(Customer customer, String oldDeviceToken, String newDeviceToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice device = authorizeUpdateCurrent(customer, oldDeviceToken, newDeviceToken, userDeviceSecurityVO)

        AuthorizationDeviceVO authorizationDeviceVO = new AuthorizationDeviceVO()
        authorizationDeviceVO.authorized = device.status == AuthorizationDeviceStatus.ACTIVE

        if (authorizationDeviceVO.authorized) {
            authorizationDeviceVO.activeDevice = device
            authorizationDeviceVO.messageCode = "authorizationDevice.main.phone.changed.success"
            authorizationDeviceVO.success = true
            return authorizationDeviceVO
        }

        AuthorizationDevice oldDevice = AuthorizationDevice.find(customer, AuthorizationDeviceType.SMS_TOKEN)
        if (oldDevice.locked) {
            authorizationDeviceVO.activeDevice = oldDevice
            authorizationDeviceVO.maxAttemptsExceeded = true
            authorizationDeviceVO.messageCode = "authorizationDevice.incorrectCode.maxAttemptsExceeded"
        } else {
            AuthorizationDevice newDevice = AuthorizationDevice.pending([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
            authorizationDeviceVO.remainingAuthorizationAttempts = newDevice.getRemainingAuthorizationAttempts()
            authorizationDeviceVO.messageCode = "authorizationDevice.incorrectCode"
            authorizationDeviceVO.messageArgs = authorizationDeviceVO.remainingAuthorizationAttempts.toString() as List
        }

        return authorizationDeviceVO
    }

    public AuthorizationDeviceVO requestResendSmsUpdateCurrent(Customer customer) {
        AuthorizationDeviceVO authorizationDeviceVO = resendSmsUpdateCurrent(customer)
        return authorizationDeviceVO
    }

    public AuthorizationDeviceVO requestActivate(Customer customer, String token, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice device = activate(customer, token, userDeviceSecurityVO)

        AuthorizationDeviceVO authorizationDeviceVO = new AuthorizationDeviceVO()
        authorizationDeviceVO.authorized = (device.status == AuthorizationDeviceStatus.ACTIVE && !device.locked)

        authorizationDeviceVO.activeDevice = device

        if (authorizationDeviceVO.authorized) {
            authorizationDeviceVO.messageCode = "authorizationDevice.main.phone.activated.success"
            authorizationDeviceVO.success = true
            return authorizationDeviceVO
        }

        if (device.locked) {
            authorizationDeviceVO.maxAttemptsExceeded = true
            authorizationDeviceVO.messageCode = "authorizationDevice.incorrectCode.maxAttemptsExceeded"
        } else {
            authorizationDeviceVO.remainingAuthorizationAttempts = device.getRemainingAuthorizationAttempts()
            authorizationDeviceVO.messageCode = "authorizationDevice.incorrectCode"
            authorizationDeviceVO.messageArgs = authorizationDeviceVO.remainingAuthorizationAttempts.toString() as List
        }

        return authorizationDeviceVO
    }

    public AuthorizationDevice validateToken(Customer customer, String newDeviceToken) {
        AuthorizationDevice newAuthorizationDevice = AuthorizationDevice.pending([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        newAuthorizationDevice.activationAttempts++

        if (newAuthorizationDevice.getDecryptedActivationToken() == newDeviceToken) {
            newAuthorizationDevice.status = AuthorizationDeviceStatus.TOKEN_VALIDATED
        }

        newAuthorizationDevice.save(failOnError: true)

        return newAuthorizationDevice
    }

    private AuthorizationDevice updateCurrent(Customer customer, String phoneNumber, AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()

        if (oldDevice.locked) throw new BusinessException("O dispositivo [${oldDevice.id}] encontra-se bloqueado, não é possível atualizá-lo.")

        String oldDeviceDeactivationToken = oldDevice.buildToken()
        oldDevice.deactivationToken = crypterService.encryptDomainProperty(oldDevice, "deactivationToken", oldDeviceDeactivationToken)
        oldDevice.save(failOnError: true)

        invalidateAllPending(customer)

        AuthorizationDevice newDevice = savePending(customer, phoneNumber, authorizationDeviceNotificationVO)
        sendDeactivationToken(oldDevice, newDevice)

        asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Alteração solicitada", [providerEmail: customer.email, phoneNumber: phoneNumber])

        return newDevice
    }

    private void cancelUpdateCurrent(Customer customer) {
        invalidateAllPending(customer)

        AuthorizationDevice currentDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        currentDevice.deactivationToken = null
        currentDevice.save(failOnError: true)

        asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Validação cancelada", [providerEmail: customer.email])
    }

    private AuthorizationDevice authorizeUpdateCurrent(Customer customer, String oldDeviceToken, String newDeviceToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()

        if (oldDevice.locked) throw new BusinessException("O dispositivo [${oldDevice.id}] encontra-se bloqueado, não é possível efetuar a autorização.")

        AuthorizationDevice newDevice = AuthorizationDevice.pending([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()

        newDevice.activationAttempts++

        if (oldDevice.getDecryptedDeactivationToken() == oldDeviceToken && newDevice.getDecryptedActivationToken() == newDeviceToken) {
            authorizationDeviceService.updateCurrentDevice(customer, newDevice, userDeviceSecurityVO)

            asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Validação confirmada", [providerEmail: customer.email, oldDeviceToken: oldDeviceToken, newDeviceToken: newDeviceToken])
        } else {
            if (newDevice.isMaxActivationAttemptsExceeded()) {
                newDevice.deleted = true
                oldDevice.locked = true

                asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Validação bloqueada", [providerEmail: customer.email, oldDeviceToken: oldDeviceToken, newDeviceToken: newDeviceToken])
            }
            asaasSegmentioService.track(customer.id, "Logged :: Celular principal :: Validação falhada", [providerEmail: customer.email, oldDeviceToken: oldDeviceToken, newDeviceToken: newDeviceToken])
        }

        oldDevice.save(failOnError: true)
        newDevice.save(failOnError: true)

        return newDevice
    }

    private AuthorizationDeviceVO resendSmsUpdateCurrent(Customer customer) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()

        if (oldDevice.locked) throw new BusinessException("O dispositivo [${oldDevice.id}] encontra-se bloqueado, não é possível atualizá-lo.")

        AuthorizationDevice newDevice = AuthorizationDevice.pending([customer: customer, type: AuthorizationDeviceType.SMS_TOKEN]).get()

        AuthorizationDeviceVO authorizationDeviceVO = new AuthorizationDeviceVO()

        if (newDevice.isMaxAttemptsToSendToken()) {
            authorizationDeviceVO.messageCode = "authorizationDevice.resendCode.maxAttemptsExceeded"
            return authorizationDeviceVO
        }

        sendActivationToken(newDevice)
        sendDeactivationToken(oldDevice, newDevice)

        authorizationDeviceVO.messageCode = "authorizationDevice.code.sent"
        authorizationDeviceVO.success = true
        return authorizationDeviceVO
    }

    private AuthorizationDevice activate(Customer customer, String activationToken, UserDeviceSecurityVO userDeviceSecurityVO) {
        AuthorizationDevice pendingDevice = authorizationDeviceService.activate(customer, AuthorizationDeviceType.SMS_TOKEN, activationToken, userDeviceSecurityVO)

        if (pendingDevice.status.isActive()) {
            return pendingDevice
        }

        if (pendingDevice.locked) {
            authorizationDeviceService.setAsActive(pendingDevice, userDeviceSecurityVO)
        }

        return pendingDevice
    }

    private void sendDeactivationToken(AuthorizationDevice oldAuthorizationDevice, AuthorizationDevice newAuthorizationDevice) {
        String message = "o código para autorizar a troca de Token SMS para ${PhoneNumberUtils.formatPhoneNumber(newAuthorizationDevice.phoneNumber)} é ${oldAuthorizationDevice.getDecryptedDeactivationToken()}"
        smsSenderService.send(message, oldAuthorizationDevice.phoneNumber, true, [isSecret: true])
    }

    private void invalidateAllPending(Customer customer) {
        AuthorizationDevice.executeUpdate("update AuthorizationDevice set deleted = true, lastUpdated = :now where customer = :customer and deleted = false and type = :type and status = :status", [now: new Date(), customer: customer, type: AuthorizationDeviceType.SMS_TOKEN, status: AuthorizationDeviceStatus.PENDING])
    }

    private void notifyAboutAuthorizationDeviceUpdateRequestedIfNecessary(AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        if (!authorizationDeviceNotificationVO) return
        if (!authorizationDeviceNotificationVO.shouldNotifyUpdateRequested) return
        if (!authorizationDeviceNotificationVO.currentUser) return

        securityEventNotificationService.notifyAndSaveHistoryAboutAuthorizationDeviceUpdateRequested(authorizationDeviceNotificationVO.currentUser)
    }
}
