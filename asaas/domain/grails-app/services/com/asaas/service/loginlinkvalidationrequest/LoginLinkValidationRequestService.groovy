package com.asaas.service.loginlinkvalidationrequest

import com.asaas.cache.loginlinkvalidationrequest.LoginLinkValidationRequestConfigCacheVO
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequest
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequestAttempt
import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorDevice
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.login.MfaType
import com.asaas.loginlinkvalidationrequest.LoginLinkValidationRequestAttemptVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class LoginLinkValidationRequestService {

    def asaasSecurityMailMessageService
    def crypterService
    def loginLinkValidationRequestAttemptService
    def loginLinkValidationRequestConfigCacheService
    def mfaService
    def userTrustedDeviceService

    public LoginLinkValidationRequest create(User user, Map params) {
        LoginLinkValidationRequest loginLinkValidationRequest = validateCreate(user, params)
        if (loginLinkValidationRequest.hasErrors()) {
            throw new ValidationException("Erro ao criar link de login para o usuário [${user?.username}]", loginLinkValidationRequest.errors)
        }

        loginLinkValidationRequest.user = user
        loginLinkValidationRequest.username = user.username

        loginLinkValidationRequest.used = false
        loginLinkValidationRequest.expirationDate = CustomDateUtils.sumMinutes(new Date(), loginLinkValidationRequestConfigCacheService.getInstance().expirationTimeInMinutes)

        loginLinkValidationRequest.token = UUID.randomUUID().toString().replaceAll('-', '').encodeAsSHA256()
        loginLinkValidationRequest.publicId = UUID.randomUUID().toString().replaceAll('-', '')

        loginLinkValidationRequest.deviceFingerprint = params.deviceFingerprint
        loginLinkValidationRequest.userAgent = params.userAgent
        loginLinkValidationRequest.latitude = params.latitude
        loginLinkValidationRequest.longitude = params.longitude
        loginLinkValidationRequest.remoteIp = params.remoteIp
        loginLinkValidationRequest.browserId = params.browserId
        loginLinkValidationRequest.city = params.ipLocationAdapter.city
        loginLinkValidationRequest.state = params.ipLocationAdapter.state
        loginLinkValidationRequest.country = params.ipLocationAdapter.country

        loginLinkValidationRequest.save(failOnError: true)

        String unencryptedToken = loginLinkValidationRequest.token
        loginLinkValidationRequest.token = crypterService.encryptDomainProperty(loginLinkValidationRequest, "token", unencryptedToken)
        loginLinkValidationRequest.save(failOnError: true)

        notifyUserLoginLinkValidationRequest(loginLinkValidationRequest)

        return loginLinkValidationRequest
    }

    public Boolean userNeedsLoginLinkValidation(Long userId, String browserId, Boolean isBeforeMfaValidation) {
        try {
            if (!AsaasEnvironment.isProduction()) return false

            if (!userId) return false

            User user = User.get(userId)
            if (!user) return false

            LoginLinkValidationRequestConfigCacheVO loginLinkValidationRequestConfigCacheVO = loginLinkValidationRequestConfigCacheService.getInstance()
            if (!loginLinkValidationRequestConfigCacheVO.enabled) return false

            if (!needsBasedOnUserMfa(user, loginLinkValidationRequestConfigCacheVO, isBeforeMfaValidation)) return false

            if (userTrustedDeviceService.existsUserTrustedDevice(user, browserId)) return false

            return true
        } catch (Exception exception) {
            AsaasLogger.error("LoginLinkValidationRequestService.userNeedsLoginLinkValidation >> Ocorreu um erro ao validar a necessidade do MFA via link de login. User [${userId}]", exception)
            return false
        }
    }

    public LoginLinkValidationRequestAttemptVO validateAndAuthorizeIfPossible(String publicId, String token, Map params) {
        validateDeviceParams(params)

        LoginLinkValidationRequest loginLinkValidationRequest = null

        if (publicId && token) {
            loginLinkValidationRequest = LoginLinkValidationRequest.query([publicId: publicId]).get()
        }

        if (!loginLinkValidationRequest) {
            throw new BusinessException(Utils.getMessageProperty("springSecurity.errors.loginLinkValidationRequest.notFound"))
        }

        LoginLinkValidationRequest validatedLoginLinkValidationRequest = validate(loginLinkValidationRequest, token)
        Boolean isLinkValid = !validatedLoginLinkValidationRequest.hasErrors()
        Boolean isRulesValid = validateIdentifierRules(loginLinkValidationRequest, params)

        LoginLinkValidationRequestAttempt linkValidationRequestAttempt = loginLinkValidationRequestAttemptService.create(loginLinkValidationRequest, isLinkValid, isRulesValid, params)
        updatedAsUsed(loginLinkValidationRequest)

        if (!isLinkValid) return new LoginLinkValidationRequestAttemptVO(linkValidationRequestAttempt, null, DomainUtils.getValidationMessages(validatedLoginLinkValidationRequest.errors))

        Boolean enabledValidation = loginLinkValidationRequestConfigCacheService.getInstance().enabledValidation
        if (enabledValidation && !isRulesValid) {
            DomainUtils.addError(validatedLoginLinkValidationRequest, Utils.getMessageProperty("springSecurity.errors.loginLinkValidationRequest.validationError"))
            return new LoginLinkValidationRequestAttemptVO(linkValidationRequestAttempt, null, DomainUtils.getValidationMessages(validatedLoginLinkValidationRequest.errors))
        }

        saveUserTrustedDevice(loginLinkValidationRequest.username, linkValidationRequestAttempt.browserId)
        return new LoginLinkValidationRequestAttemptVO(linkValidationRequestAttempt, loginLinkValidationRequest.username, null)
    }

    private Boolean isTokenValid(LoginLinkValidationRequest loginLinkValidationRequest, String inputToken) {
        if (!inputToken) return false

        return inputToken == loginLinkValidationRequest.buildDecryptedToken()
    }

    private LoginLinkValidationRequest validateCreate(User user, Map params) {
        LoginLinkValidationRequest loginLinkValidationRequest = new LoginLinkValidationRequest()

        if (!user) {
            DomainUtils.addError(loginLinkValidationRequest, "É obrigatório informar um usuário válido")
            return loginLinkValidationRequest
        }

        if (!params.remoteIp) {
            DomainUtils.addError(loginLinkValidationRequest, "Não foi possível capturar o remoteIp da requisição")
            return loginLinkValidationRequest
        }

        return loginLinkValidationRequest
    }

    private LoginLinkValidationRequest validate(LoginLinkValidationRequest loginLinkValidationRequest, String token) {
        LoginLinkValidationRequest validatedLoginLinkValidationRequest = new LoginLinkValidationRequest()

        if (loginLinkValidationRequest.isExpired() || loginLinkValidationRequest.used) {
            DomainUtils.addError(validatedLoginLinkValidationRequest, Utils.getMessageProperty("springSecurity.errors.loginLinkValidationRequest.invalid"))
            return validatedLoginLinkValidationRequest
        }

        if (!isTokenValid(loginLinkValidationRequest, token)) {
            DomainUtils.addError(validatedLoginLinkValidationRequest, Utils.getMessageProperty("springSecurity.errors.loginLinkValidationRequest.notFound"))
        }

        return validatedLoginLinkValidationRequest
    }

    private void saveUserTrustedDevice(String username, String browserId) {
        Long userId = User.query([column: "id", username: username, ignoreCustomer: true, disableSort: true]).get()
        userTrustedDeviceService.save(userId, browserId)
    }

    private Boolean validateIdentifierRules(LoginLinkValidationRequest loginLinkValidationRequest, Map params) {
        if (loginLinkValidationRequest.remoteIp != params.remoteIp) return false

        if (loginLinkValidationRequest.deviceFingerprint != params.deviceFingerprint) return false

        return true
    }

    private void updatedAsUsed(LoginLinkValidationRequest loginLinkValidationRequest) {
        loginLinkValidationRequest.used = true
        loginLinkValidationRequest.save(failOnError: true)
    }

    private void notifyUserLoginLinkValidationRequest(LoginLinkValidationRequest loginLinkValidationRequest) {
        Integer linkExpirationTime = loginLinkValidationRequestConfigCacheService.getInstance().expirationTimeInMinutes

        asaasSecurityMailMessageService.notifyUserLoginLinkValidationRequest(loginLinkValidationRequest, linkExpirationTime)
    }

    private void validateDeviceParams(Map params) {
        if (!AsaasEnvironment.isProduction()) return

        final String errorMessage = Utils.getMessageProperty("springSecurity.errors.loginLinkValidationRequest.deviceParamsNotFound")

        if (!params.remoteIp) throw new BusinessException(errorMessage)

        if (!params.deviceFingerprint) throw new BusinessException(errorMessage)

        if (!params.browserId) throw new BusinessException(errorMessage)
    }

    private Boolean needsBasedOnUserMfa(User user, LoginLinkValidationRequestConfigCacheVO loginLinkValidationRequestConfigCacheVO, Boolean isBeforeMfaValidation) {
        if (!user.mfaEnabled) mfaService.registerAndEnableMfaIfNecessary(user, true)

        MfaType mfaType = UserMultiFactorDevice.query([column: "mfaType", user: user]).get()
        if (!mfaType) {
            AsaasLogger.warn("LoginLinkValidationRequestService.needsBasedOnUserMfa >> Usuário não possui MFA registrado. Username [${user.username}]")
            return false
        }

        if (mfaType.isEmail()) {
            if (!Utils.isPropertyInPercentageRange(user.customerId, loginLinkValidationRequestConfigCacheVO.percentEnabledEmail)) return false
        }

        if (mfaType.isSMS()) {
            if (!Utils.isPropertyInPercentageRange(user.customerId, loginLinkValidationRequestConfigCacheVO.percentEnabledSms)) return false
        }

        if (mfaType.isTotp()) return false

        if (isBeforeMfaValidation && !mfaType.isEmail()) return false

        return true
    }
}
