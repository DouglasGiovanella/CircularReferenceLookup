package com.asaas.service.login

import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorAuth
import com.asaas.domain.user.UserMultiFactorDevice
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.image.QrCodeGenerator
import com.asaas.log.AsaasLogger
import com.asaas.login.MfaStatus
import com.asaas.login.MfaType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.warrenstrange.googleauth.GoogleAuthenticator
import com.warrenstrange.googleauth.GoogleAuthenticatorKey
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator
import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils
import org.springframework.security.authentication.AuthenticationServiceException

import javax.servlet.http.HttpSession

@Transactional
class MfaService {

    def asaasSecurityMailMessageService
    def crypterService
    def mfaEnablingDeadlineService
    def passwordEncoder
    def userMultiFactorDeviceService
    def userTrustedDeviceService
    def smsSenderService

    public Map buildMfaAuthenticatorData() {
        User user = UserUtils.getCurrentUser()
        GoogleAuthenticator gAuth = new GoogleAuthenticator()
        GoogleAuthenticatorKey authenticatorKey = gAuth.createCredentials()

        String totpUri = GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL("Asaas", user.username, authenticatorKey)

        byte[] qrCodeImage = QrCodeGenerator.generateImage(totpUri)

        Boolean isUserTotpEnabled = UserMultiFactorDevice.query([exists: true, user: user, mfaType: MfaType.TOTP]).get().asBoolean()

        return [totpUri: totpUri, qrCodeImage: qrCodeImage, authenticatorKey: authenticatorKey.key, totpEnabled: isUserTotpEnabled]
    }

    public UserMultiFactorAuth generateUserMfa(User user) {
        UserMultiFactorDevice userMultiFactorDevice = UserMultiFactorDevice.query(user: user).get()

        if (!userMultiFactorDevice) {
            AsaasLogger.error("Inconsistência no MFA. Não encontrado MFA device para o usuário [${user.id}].")
            throw new AuthenticationServiceException("Inconsistência no MFA. Não encontrado dispositivo MFA para o usuário [${user.id}].")
        }

        if (!validateUserMfaType(userMultiFactorDevice)) {
            AsaasLogger.error("Inconsistência no MFA. Usuário [${user.id}] com configuração errada.")
            throw new AuthenticationServiceException("Inconsistência no MFA. Usuário [${user.id}] com configuração errada.")
        }

        UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.preAuth([device: userMultiFactorDevice, "expirationDate[ge]": new Date()]).get()
        if (!userMultiFactorAuth) userMultiFactorAuth = createUserMultiFactorAuth(userMultiFactorDevice)

        sendUserMfaCodeIfNecessary(userMultiFactorAuth)

        return userMultiFactorAuth
    }

    public Boolean authorize(String userMultiFactorAuthId, String userInputMfa, String deviceId) {
        UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.preAuth([publicId: userMultiFactorAuthId]).get()

        return authorize(userMultiFactorAuth, userInputMfa, deviceId, true)
    }

    public Boolean authorize(UserMultiFactorAuth userMultiFactorAuth, String userInputMfa, String deviceId, Boolean shouldCreateUserTrustedDevice) {
        if (!userMultiFactorAuth) {
            return false
        }

        if (validateUserMfa(userMultiFactorAuth, userInputMfa)) {
            userMultiFactorAuth.status = MfaStatus.AUTH_SUCCESSED
        } else {
            userMultiFactorAuth.status = MfaStatus.AUTH_FAILED
        }

        userMultiFactorAuth.save(failOnError: true)

        Boolean successed = userMultiFactorAuth.status == MfaStatus.AUTH_SUCCESSED

        if (shouldCreateUserTrustedDevice && successed) {
            userTrustedDeviceService.save(userMultiFactorAuth.user.id, deviceId)
        }

        return successed
    }

    public Boolean userNeedsMfaCodeValidation(Long userId, String device) {
        if (AsaasEnvironment.isSandbox()) return false

        if (!userId) return false

        User user = User.get(userId)
        if (!user) return false

        if (mfaEnablingDeadlineService.hasMfaEnablingDeadlineActive(user)) return false

        if (userTrustedDeviceService.existsUserTrustedDevice(user, device)) return false

        if (!user.mfaEnabled) registerAndEnableMfaIfNecessary(user)

        if (!user.mfaEnabled) return false

        return true
    }

    public Boolean isInternalUserWithoutTotp(User user) {
        if (!user.sysAdmin()) return false
        if (UserMultiFactorDevice.query([exists: true, user: user, mfaType: MfaType.TOTP]).get().asBoolean()) return false
        return true
    }

    public Boolean authorizeUserActionWithTotp(User user, String userInputMfa) {
        UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.query([user: user]).get()
        if (!userMultiFactorAuth) throw new BusinessException("Usuário sem MFA ativo.")
        if (!userMultiFactorAuth.device.mfaType.isTotp()) throw new BusinessException("Tipo de autorização multifator inválida.")

        if (validateTotpCode(userMultiFactorAuth, userInputMfa)) return true

        throw new BusinessException("Falha na validação do autenticador.")
    }

    public void prepareUserMfaCodeValidation(String username, HttpSession session) {
        User user = User.notExpiredOrLocked(username).get()

        UserMultiFactorAuth userMultiFactorAuth = generateUserMfa(user)

        session.setAttribute("userMultiFactorAuthId", userMultiFactorAuth.publicId)

        Integer tenMinutesInSeconds = 600
        session.setMaxInactiveInterval(tenMinutesInSeconds)
    }

    public void resendUserMfaCode(String userMultiFactorAuthId) {
        UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.preAuth([publicId: userMultiFactorAuthId]).get()

        if (!userMultiFactorAuth) {
            return
        }

        sendUserMfaCodeIfNecessary(userMultiFactorAuth)
    }

    public void registerAndEnableMfaIfNecessary(User user) {
        registerAndEnableMfaIfNecessary(user, false)
    }

    public void registerAndEnableMfaIfNecessary(User user, Boolean flushOnSave) {
        if (user.sysAdmin()) return

        if (user.mobilePhone) {
            userMultiFactorDeviceService.registerSMS(user, user.mobilePhone, flushOnSave)
            return
        }

        userMultiFactorDeviceService.registerEmail(user, flushOnSave)
    }

    public Long getRemainingTimeToResendMfaCodeInSeconds(UserMultiFactorAuth userMultiFactorAuth) {
        if (userMultiFactorAuth.type.isTotp()) return 0

        Date dateAllowedToResend = CustomDateUtils.sumSeconds(userMultiFactorAuth.lastAttemptDate, UserMultiFactorAuth.TIME_TO_RESEND_MFA_CODE_IN_SECONDS)
        Date now = new Date()

        Long remainingTimeInSeconds = (Long) ((dateAllowedToResend.getTime() - now.getTime()) / 1000)
        return remainingTimeInSeconds
    }

    private Boolean validateUserMfa(UserMultiFactorAuth userMultiFactorAuth, String userInputMfa) {
        if (userMultiFactorAuth.expirationDate < new Date()) return false

        if (userMultiFactorAuth.type == MfaType.TOTP) {
            return validateTotpCode(userMultiFactorAuth, userInputMfa)
        }

        if (userMultiFactorAuth.type.isEmailOrSms()) {
            if (userMultiFactorAuth.getDecryptedCode() == userInputMfa) return true

            return passwordEncoder.isPasswordValid(userMultiFactorAuth.code, userInputMfa, null)
        }

        return false
    }

    private Boolean validateTotpCode(UserMultiFactorAuth userMultiFactorAuth, String userInputMfa) {
        if (!userInputMfa.isNumber()) return false

        String mfaSharedKey = crypterService.decryptDomainProperty(userMultiFactorAuth.device, "totpSharedKey", userMultiFactorAuth.device.totpSharedKey)

        GoogleAuthenticator gAuth = new GoogleAuthenticator()
        return gAuth.authorize(mfaSharedKey, userInputMfa.toInteger())
    }

    private void sendUserMfaCodeIfNecessary(UserMultiFactorAuth userMultiFactorAuth) {
        if (!canSendUserMfaCode(userMultiFactorAuth)) return

        String mfaCode = userMultiFactorAuth.getDecryptedCode()
        MfaType mfaType = userMultiFactorAuth.device.mfaType

        if (mfaType.isSMS()) {
            String textToSend = "o código para autorizar um novo acesso a conta Asaas é ${mfaCode}. Nunca revele esse código. asaas.com"
            smsSenderService.send(textToSend, userMultiFactorAuth.device.mobilePhone, true, [isSecret: true])
            AsaasLogger.debug("${userMultiFactorAuth.device.mobilePhone} >> ${textToSend}")
        } else if (mfaType.isEmail()) {
            asaasSecurityMailMessageService.sendUserMfaCode(userMultiFactorAuth.user, mfaCode)
        }

        userMultiFactorAuth.attemptsToSend = userMultiFactorAuth.attemptsToSend ? userMultiFactorAuth.attemptsToSend + 1 : 1
        userMultiFactorAuth.lastAttemptDate = new Date()
        userMultiFactorAuth.save()
    }

    private Boolean canSendUserMfaCode(UserMultiFactorAuth userMultiFactorAuth) {
        if (!userMultiFactorAuth.device.mfaType.isEmailOrSms()) return false

        if (userMultiFactorAuth.attemptsToSend >= UserMultiFactorAuth.MAX_ATTEMPTS_TO_SEND) return  false

        Date oneMinuteAgo = CustomDateUtils.sumSeconds(new Date(), UserMultiFactorAuth.TIME_TO_RESEND_MFA_CODE_IN_SECONDS * -1)
        if (userMultiFactorAuth.lastAttemptDate > oneMinuteAgo) return false

        return true
    }

    private Boolean validateUserMfaType(UserMultiFactorDevice userMultiFactorDevice) {
        if (userMultiFactorDevice.mfaType == MfaType.SMS && !userMultiFactorDevice.mobilePhone) {
            return false
        }

        if (userMultiFactorDevice.mfaType == MfaType.TOTP && !userMultiFactorDevice.totpSharedKey) {
            return false
        }

        return true
    }

    private UserMultiFactorAuth createUserMultiFactorAuth(UserMultiFactorDevice userMultiFactorDevice) {
        final Integer userMultiFactorAuthDefaultExpirationTimeInMinutes = 5

        UserMultiFactorAuth userMultiFactorAuth = new UserMultiFactorAuth()

        userMultiFactorAuth.user = userMultiFactorDevice.user
        userMultiFactorAuth.type = userMultiFactorDevice.mfaType
        userMultiFactorAuth.status = MfaStatus.PRE_AUTH
        userMultiFactorAuth.device = userMultiFactorDevice
        userMultiFactorAuth.publicId = UUID.randomUUID()
        userMultiFactorAuth.expirationDate = CustomDateUtils.sumMinutes(new Date(), userMultiFactorAuthDefaultExpirationTimeInMinutes)
        userMultiFactorAuth.save(failOnError: true, flush: true)

        if (userMultiFactorDevice.mfaType.isEmailOrSms()) {
            String mfaCode = AsaasEnvironment.isDevelopment() ? "000000" : RandomStringUtils.randomNumeric(6)
            userMultiFactorAuth.code = crypterService.encryptDomainProperty(userMultiFactorAuth, "code", mfaCode)
            userMultiFactorAuth.save(failOnError: true, flush: true)
        }

        return userMultiFactorAuth
    }
}
