package com.asaas.service.securityeventnotification

import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.user.User
import com.asaas.mobilepushnotification.MobilePushNotificationAction
import com.asaas.utils.EmailUtils
import grails.transaction.Transactional

@Transactional
class SecurityEventNotificationService {

    def asaasSecurityMailMessageService
    def customerAlertNotificationService
    def customerInteractionService
    def mobilePushNotificationService

    public void notifyAndSaveHistoryAboutNewDevice(UserKnownDevice userKnownDevice) {
        User user = userKnownDevice.user
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        asaasSecurityMailMessageService.notifyNewUserKnownDevice(userKnownDevice)
        customerAlertNotificationService.notifyNewUserKnownDevice(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} realizou um acesso na conta por meio de um novo dispositivo.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.NEW_USER_KNOWN_DEVICE)
    }

    public void notifyAndSaveHistoryAboutUserPasswordUpdated(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyUserPasswordUpdated(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} redefiniu a senha de acesso.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.USER_PASSWORD_UPDATED)
    }

    public void notifyAndSaveHistoryAboutUserUpdated(User user, User updatedByUser) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyUserUpdated(user, updatedByUser)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} teve seus dados editados.")

        User updatedBy = updatedByUser ? updatedByUser : user
        mobilePushNotificationService.notifyUserSecurityEvent(updatedBy, MobilePushNotificationAction.USER_UPDATED)
    }

    public void notifyAndSaveHistoryAboutApiKeyCreated(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyApiKeyCreated(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} criou uma chave de API.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.API_KEY_CREATED)
    }

    public void notifyAndSaveHistoryAboutPasswordRecoveryAttempt(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyPasswordRecoveryAttempt(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} tentou redefinir a senha de acesso.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.PASSWORD_RECOVERY_ATTEMPT)
    }

    public void notifyAndSaveHistoryAboutCriticalActionConfigDisabled(User user, String criticalActionConfigDisabledMessage) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyCriticalActionConfigDisabled(user, criticalActionConfigDisabledMessage)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} desabilitou os eventos críticos de ${criticalActionConfigDisabledMessage}.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.CRITICAL_ACTION_CONFIG_DISABLED)
    }

    public void notifyAndSaveHistoryAboutFacematchCriticalActionAuthorized(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyFacematchCriticalActionAuthorized(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "Um evento crítico foi validado via facematch pelo usuário ${maskedUsername}.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.FACEMATCH_CRITICAL_ACTION_AUTHORIZED)
    }

    public void notifyAndSaveHistoryAboutUserCreation(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        asaasSecurityMailMessageService.notifyUserCreation(user)
        customerAlertNotificationService.notifyCustomerOnUserCreation(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} foi criado.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.USER_CREATION)
    }

    public void notifyAndSaveHistoryAboutSelfieUpload(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        asaasSecurityMailMessageService.notifySelfieUpload(user)
        customerAlertNotificationService.notifyCustomerOnSelfieUpload(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} fez upload de uma selfie.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.SELFIE_UPLOAD)
    }

    public void notifyAboutUserPasswordExpiration(User user, Map options) {
        Integer daysToExpire = options.daysToExpire
        if (options.isMail) {
            asaasSecurityMailMessageService.notifyUserPasswordExpiration(user, daysToExpire)
        }
        if (options.isMobilePush) {
            mobilePushNotificationService.notifyUserPasswordExpiration(user, daysToExpire)
        }
    }

    public void notifyAndSaveHistoryAboutCustomerUpdateRequested(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyCustomerUpdateRequested(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} tentou atualizar os dados comerciais da conta.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.CUSTOMER_UPDATE_REQUESTED)
    }

    public void notifyAndSaveHistoryAboutAuthorizationDeviceUpdateRequested(User user) {
        String maskedUsername = EmailUtils.formatEmailWithMask(user.username)

        customerAlertNotificationService.notifyAuthorizationDeviceUpdateRequested(user)
        customerInteractionService.saveSecurityEvent(user.customerId, "O usuário ${maskedUsername} tentou alterar o dispositivo de autorização.")
        mobilePushNotificationService.notifyUserSecurityEvent(user, MobilePushNotificationAction.AUTHORIZATION_DEVICE_UPDATE_REQUESTED)
    }
}
