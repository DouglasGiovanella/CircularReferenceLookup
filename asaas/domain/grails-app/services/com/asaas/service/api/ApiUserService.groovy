package com.asaas.service.api

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiUserParser
import com.asaas.customer.CustomerValidator
import com.asaas.customer.adapter.CreateApiUserCustomerAdapter
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorAuth
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.login.LoginAttemptAction
import com.asaas.login.LoginAttemptAdapter
import com.asaas.login.LoginValidationResult
import com.asaas.login.UserKnownDeviceAdapter
import com.asaas.platform.Platform
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ApiUserService extends ApiBaseService {

    def abTestService
    def apiResponseBuilderService
    def connectedAccountInfoHandlerService
    def createCustomerService
    def customerImeiService
    def customerProofOfLifeService
    def grailsApplication
    def hubspotEventService
    def loginAttemptService
    def loginUnlockRequestResultProcessService
    def loginUnlockRequestService
    def loginValidationService
    def mfaService
    def userApiKeyService
    def userKnownDeviceService
    def userMobileDeviceService
    def userPasswordManagementService
    def userService
    def userTrustedDeviceService

    public Map authenticate(Map params) {
        Map fields = ApiUserParser.parseAuthenticateParams(params)

        User user = validateAuthentication(fields)
        if (user.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(user, [originalErrorCode: true])
        }

        if (mfaService.userNeedsMfaCodeValidation(user.id, fields.distinctId)) {
            UserMultiFactorAuth userMultiFactorAuth = mfaService.generateUserMfa(user)

            Map mfaMap = [:]
            mfaMap.id = userMultiFactorAuth.publicId
            mfaMap.type = userMultiFactorAuth.type.toString()
            mfaMap.email = userMultiFactorAuth.user.username
            mfaMap.mobilePhone = userMultiFactorAuth.device.mobilePhone
            mfaMap.timeToResendMfaCodeInSeconds = UserMultiFactorAuth.TIME_TO_RESEND_MFA_CODE_IN_SECONDS
            mfaMap.supportNumber = grailsApplication.config.asaas.mobilephone
            mfaMap.supportEmail = grailsApplication.config.asaas.ombudsman.mail

            return apiResponseBuilderService.buildSuccess([mfaInfo: mfaMap])
        }

        Map unlockLoginData = processUnlockLoginIfNecessary(user)
        if (unlockLoginData) {
            return apiResponseBuilderService.buildSuccess(unlockLoginData)
        }

        String accessToken

        if (user.passwordExpired) {
            return apiResponseBuilderService.buildErrorFrom(LoginValidationResult.PASSWORD_EXPIRED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.passwordExpired"))
        }

        Boolean isMobileReauthentication = getProvider().asBoolean()

        if (!isMobileReauthentication) {
            accessToken = userApiKeyService.generateAccessToken(user, fields.distinctId)
        }

        processAuthenticationSucceeded(user, LoginAttemptAction.AUTHENTICATE, fields)

        return apiResponseBuilderService.buildSuccess(ApiUserParser.buildResponseItem(user, accessToken))
    }

    public Map save(Map params) {
        Map fields = ApiUserParser.parseSaveParams(params)

        CustomerValidator customerValidator = new CustomerValidator()
        BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(fields.email, true)
        if (!validatedEmail.isValid()) {
            return apiResponseBuilderService.buildErrorFrom("email_in_use", validatedEmail.asaasErrors.collect { it.getMessage() }.join(" - "))
        }

        if (!ApiMobileUtils.hasMinimumVersionAllowed()) {
            return apiResponseBuilderService.buildErrorFrom("unsupported_version", "Para melhor segurança da sua conta, atualize seu aplicativo para a ultima versão.")
        }

        CreateApiUserCustomerAdapter customerAdapter = CreateApiUserCustomerAdapter.build(fields)
        Customer customer = createCustomerService.save(customerAdapter)
        if (customer.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customer)
        }

        User user = User.query([customer: customer]).get()

        String accessToken = userApiKeyService.generateAccessToken(user, fields.distinctId)

        customerProofOfLifeService.updateToSelfieIfPossible(user.customer)

        if (fields.canDrawHorizontalPaymentWizardAbTest) {
            drawHorizontalPaymentWizardAbTestIfNecessary(user.customer)
        }

        processAuthenticationSucceeded(user, LoginAttemptAction.SAVE, fields)

        return apiResponseBuilderService.buildSuccess(ApiUserParser.buildResponseItem(user, accessToken))
    }

    public Map logout(Map params) {
        userMobileDeviceService.delete(params.mobileToken)
        userKnownDeviceService.deactivateCurrent(UserUtils.getCurrentUser().id)

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map validateEmailInUse(Map params) {
        CustomerValidator customerValidator = new CustomerValidator()
        BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(params.email, true)
        if (!validatedEmail.isValid()) {
            return apiResponseBuilderService.buildErrorFrom("email_in_use", validatedEmail.asaasErrors.collect { it.getMessage() }.join(" - "))
        }

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map changePassword(Map params) {
        Map fields = ApiUserParser.parseChangePasswordParams(params)

        User user = UserUtils.getCurrentUser()

        Map changePasswordResponse = userService.changePassword(user, fields)

        String message = Utils.getMessageProperty(changePasswordResponse.messageCode, changePasswordResponse.args)

        if (!changePasswordResponse.success) {
            return apiResponseBuilderService.buildErrorFrom(changePasswordResponse.messageCode, message)
        }

        String accessToken
        if (fields.generateApiKey) {
            accessToken = userApiKeyService.generateAccessToken(user, fields.distinctId)
        }

        processAuthenticationSucceeded(user, LoginAttemptAction.RESET_PASSWORD, fields)

        return apiResponseBuilderService.buildSuccess([message: message, apiKey: accessToken])
    }

    public Map resetExpiredPassword(Map params) {
        Map fields = ApiUserParser.parseResetExpiredPasswordParams(params)

        User user = UserUtils.getCurrentUser()

        if (!user) {
            user = validateAuthentication(fields)

            if (user.hasErrors()) {
                AsaasLogger.warn("ApiUserService.resetExpiredPassword >> Usuário com errors: ${user.errors} | user: ${user.id}, remoteIp: ${RequestUtils.getRemoteIp()}, headers: ${RequestUtils.getHeadersString()}")
                return apiResponseBuilderService.buildNotFoundItem()
            }
        }

        if (!user.passwordExpired) {
            AsaasLogger.warn("ApiUserService.resetExpiredPassword >> Senha não expirada | user: ${user.id}, remoteIp: ${RequestUtils.getRemoteIp()}, headers: ${RequestUtils.getHeadersString()}")
            return apiResponseBuilderService.buildNotFoundItem()
        }

        if (mfaService.userNeedsMfaCodeValidation(user.id, fields.distinctId)) {
            AsaasLogger.warn("ApiUserService.resetExpiredPassword >> MFA Pendente | user: ${user.id}, remoteIp: ${RequestUtils.getRemoteIp()}, headers: ${RequestUtils.getHeadersString()}")
            return apiResponseBuilderService.buildNotFoundItem()
        }

        if (loginUnlockRequestService.userNeedsUnlockLogin(user.username)) {
            AsaasLogger.warn("ApiUserService.resetExpiredPassword >> LoginUnlock Pendente | user: ${user.id}, remoteIp: ${RequestUtils.getRemoteIp()}, headers: ${RequestUtils.getHeadersString()}")
            return apiResponseBuilderService.buildNotFoundItem()
        }

        userPasswordManagementService.validateAndUpdatePassword(user.username, fields.newPassword, fields.newPasswordConfirm)

        String accessToken = userApiKeyService.generateAccessToken(user, fields.distinctId)

        processAuthenticationSucceeded(user, LoginAttemptAction.RESET_EXPIRED_PASSWORD, fields)

        return apiResponseBuilderService.buildSuccess(ApiUserParser.buildResponseItem(user, accessToken))
    }

    public Map getModulePermission(Map params) {
        User user = userService.findUser(params.userId, params.provider)

        return apiResponseBuilderService.buildSuccess([modules: user.getAllowedModules()])
    }

    public Map saveCustomerImei(Map params) {
        User user = UserUtils.getCurrentUser()
        String remoteIp = RequestUtils.getRemoteIp()

        customerImeiService.saveImeis(user, params.imeiList, ApiMobileUtils.getMobileAppPlatform(), ApiMobileUtils.getDeviceName())
        connectedAccountInfoHandlerService.saveLoginInfoIfPossible(user, remoteIp, params.imeiList.join(',').replace("\"", ""))

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map authorizeMfa(Map params) {
        Map fields = ApiUserParser.parseAuthorizeMfaParams(params)

        if (!fields.code || !fields.id) {
            return apiResponseBuilderService.buildErrorFrom("code_and_id_mandatory", "É necessário informar o id recebido e o code")
        }

        if (mfaService.authorize(fields.id, fields.code, fields.distinctId)) {
            UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.query([publicId: fields.id]).get()

            Map unlockLoginData = processUnlockLoginIfNecessary(userMultiFactorAuth.user)
            if (unlockLoginData) {
                return apiResponseBuilderService.buildSuccess(unlockLoginData)
            }

            if (userMultiFactorAuth.user.passwordExpired) {
                return apiResponseBuilderService.buildErrorFrom(LoginValidationResult.PASSWORD_EXPIRED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.passwordExpired"))
            }

            String accessToken = userApiKeyService.generateAccessToken(userMultiFactorAuth.user, fields.distinctId)

            processAuthenticationSucceeded(userMultiFactorAuth.user, LoginAttemptAction.MFA_AUTHORIZATION, fields)

            return apiResponseBuilderService.buildSuccess(ApiUserParser.buildResponseItem(userMultiFactorAuth.user, accessToken))
        }

        loginAttemptService.saveFailure(new LoginAttemptAdapter(LoginAttemptAction.MFA_AUTHORIZATION, fields.deviceFingerprint))

        return apiResponseBuilderService.buildErrorFrom("incorrect_code", "Código de verificação em duas etapas incorreto, tente novamente.")
    }

    public Map resendMfaCode(Map params) {
        mfaService.resendUserMfaCode(params.userMultiFactorAuthId)

        return apiResponseBuilderService.buildSuccess([success: true])
    }

    public Map authenticateLoginUnlock(Map params) {
        Map fields = ApiUserParser.parseAuthenticateLoginUnlockParams(params)

        User user = User.notExpiredOrLocked(fields.username).get()
        LoginUnlockRequest loginUnlockRequest = LoginUnlockRequest.query([publicId: fields.id, customer: user.customer]).get()

        if (loginUnlockRequest.status.isAwaitingAuthentication()) {
            loginUnlockRequestResultProcessService.onAuthentication(loginUnlockRequest)

            if (user.passwordExpired) {
                return apiResponseBuilderService.buildErrorFrom(LoginValidationResult.PASSWORD_EXPIRED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.passwordExpired"))
            }

            String accessToken = userApiKeyService.generateAccessToken(user, fields.distinctId)

            processAuthenticationSucceeded(user, LoginAttemptAction.FACEMATCH_UNLOCK, fields)

            return apiResponseBuilderService.buildSuccess(ApiUserParser.buildResponseItem(user, accessToken))
        }

        return apiResponseBuilderService.buildUnauthorized()
    }

    public Map getLoginUnlockRequest(Map params) {
        Map fields = ApiUserParser.buildLoginUnlockParamsFromEncryptedKey(params.key)

        if (!fields.username || !fields.id) return apiResponseBuilderService.buildNotFoundItem()

        User user = User.notExpiredOrLocked(fields.username).get()

        LoginUnlockRequest loginUnlockRequest = LoginUnlockRequest.query([publicId: fields.id, customer: user.customer]).get()

        if (loginUnlockRequest.status.isFinished()) {
            Map loginUnlockRequestMap = [:]
            loginUnlockRequestMap.status = loginUnlockRequest.status.toString()
            loginUnlockRequestMap.id = loginUnlockRequest.publicId
            loginUnlockRequestMap.manualAnalysisDateCreated = loginUnlockRequest.lastUpdated

            return apiResponseBuilderService.buildSuccess(loginUnlockRequestMap)
        }

        return apiResponseBuilderService.buildSuccess(buildLoginUnlockRequestResponse(user))
    }

    public Map reauthenticateWithFingerprint(Map params) {
        Map fields = ApiUserParser.parseRequestParams(params)

        User user = UserUtils.getCurrentUser()

        return reauthenticate(user, LoginAttemptAction.REAUTHENTICATE_WITH_FINGERPRINT, fields)
    }

    public Map reauthenticateWithPassword(Map params) {
        Map fields = ApiUserParser.parseAuthenticateParams(params)

        User user = validateAuthentication(fields)
        if (user.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(user, [originalErrorCode: true])
        }

        return reauthenticate(user, LoginAttemptAction.REAUTHENTICATE_WITH_PASSWORD, fields)
    }

    private Map reauthenticate(User user, LoginAttemptAction loginAttemptAction, Map fields) {
        Map unlockLoginData = processUnlockLoginIfNecessary(user)
        if (unlockLoginData) {
            return apiResponseBuilderService.buildUnauthorized()
        }

        if (user.passwordExpired) {
            if (mfaService.userNeedsMfaCodeValidation(user.id, fields.distinctId)) {
                return apiResponseBuilderService.buildUnauthorized()
            }

            return apiResponseBuilderService.buildErrorFrom(LoginValidationResult.PASSWORD_EXPIRED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.passwordExpired"))
        }

        processAuthenticationSucceeded(user, loginAttemptAction, fields)

        return apiResponseBuilderService.buildSuccess([:])
    }

    private Map buildLoginUnlockRequestResponse(User user) {
        Map unlockData = loginUnlockRequestService.receiveUnlockLoginData(user, true)

        Map responseMap = ApiUserParser.buildGetLoginUnlockRequestResponseItem(unlockData)
        responseMap.userEmail = user.username
        responseMap.passwordExpired = user.passwordExpired

        return responseMap
    }

    private Map processUnlockLoginIfNecessary(User user) {
        if (loginUnlockRequestService.userNeedsUnlockLogin(user.username)) {
            Map loginUnlockRequestMap = buildLoginUnlockRequestResponse(user)

            LoginUnlockRequest loginUnlockRequest = LoginUnlockRequest.query([publicId: loginUnlockRequestMap.id, customer: user.customer]).get()
            if (loginUnlockRequest.status.isAwaitingAuthentication()) {
                loginUnlockRequestResultProcessService.onAuthentication(loginUnlockRequest)
            } else {
                Map responseMap = [:]
                responseMap.loginUnlockRequest = loginUnlockRequestMap

                return responseMap
            }
        }

        return null
    }

    private User validateAuthentication(Map params) {
        String headers = RequestUtils.getHeadersString()
        String remoteIp = RequestUtils.getRemoteIp()

        Map logParams = params.clone()
        logParams.password = "****"
        logParams.passwordConfirm = "****"
        logParams.mobileApitoken = "****"

        if (loginValidationService.verifyAndBlockIpIfNecessary(remoteIp)) {
            throw new BusinessException(Utils.getMessageProperty("springSecurity.errors.login.fail"))
        }

        Boolean shouldBypassExpiredPasswordValidation = params.supportsInAppExpiredPasswordReset
        User user = loginValidationService.validateLogin(params.username, params.password, new LoginAttemptAdapter(LoginAttemptAction.AUTHENTICATE, params.deviceFingerprint), shouldBypassExpiredPasswordValidation)
        if (user.hasErrors()) {
            AsaasLogger.warn("API Login Attempt Failure - User not found. user: ${params.username}, params: ${logParams}, headers: ${headers}, errors: ${DomainUtils.getValidationMessagesAsString(user.errors)}")
            return user
        }

        if (getProvider() && user.customer.id != getProvider()) {
            loginAttemptService.saveBlocked(new LoginAttemptAdapter(LoginAttemptAction.AUTHENTICATE, params.deviceFingerprint))
            AsaasLogger.warn("API Login Attempt Failure - Not the same customer. user: ${params.username}, params: ${logParams}, headers: ${headers}, customerIdFromRequest: ${getProvider()}")
            loginValidationService.trackLoginAuthenticationFailure(user, "not_same_customer")

            return DomainUtils.addErrorWithErrorCode(user, "invalid_username_or_password", "Login ou senha inválidos")
        }

        if (ApiMobileUtils.isAndroidAppRequest()) {
            final Integer androidLollipopApiVersion = 21
            Boolean isAndroidVersionDiscontinued = Utils.toInteger(ApiMobileUtils.getOperationSystemVersion()) < androidLollipopApiVersion
            if (isAndroidVersionDiscontinued) {
                loginAttemptService.saveBlocked(new LoginAttemptAdapter(LoginAttemptAction.AUTHENTICATE, params.deviceFingerprint))
                AsaasLogger.warn("API Login Attempt Failure - pre lollipop android version. user: ${params.username}, params: ${logParams}, headers: ${headers}")
                loginValidationService.trackLoginAuthenticationFailure(user, "unsupported_operation_system")

                return DomainUtils.addErrorWithErrorCode(user, "unsupported_operation_system", "O suporte a versões Android anteriores a Lollipop foi descontinuada.")
            }
        }

        if (ApiMobileUtils.isMobileAppRequest() && !ApiMobileUtils.hasMinimumVersionAllowed()) {
            loginAttemptService.saveBlocked(new LoginAttemptAdapter(LoginAttemptAction.AUTHENTICATE, params.deviceFingerprint))
            AsaasLogger.warn("API Login Attempt Failure - using old mobile app version. user: ${params.username}, params: ${logParams}, headers: ${headers}")
            loginValidationService.trackLoginAuthenticationFailure(user, "old_mobile_app_version")

            return DomainUtils.addErrorWithErrorCode(user, "unsupported_version", "Para melhor segurança da sua conta, atualize seu aplicativo para a ultima versão.")
        }

        return user
    }

    private void processAuthenticationSucceeded(User user, LoginAttemptAction action, Map params) {
        Long deviceUserApiKeyId = UserApiKey.query(["column": "id", user: user, device: params.distinctId]).get()
        if (!deviceUserApiKeyId) {
            Long userApiKeyFromCurrentRequest = Utils.toLong(RequestUtils.getParameter("userApiKeyId"))
            if (userApiKeyFromCurrentRequest) {
                deviceUserApiKeyId = userApiKeyFromCurrentRequest
            } else {
                throw new RuntimeException("O dispositivo: ${params.distinctId} se autenticou via API e não possui uma chave de API gerada.")
            }
        }

        LoginAttemptAdapter loginAttemptAdapter = new LoginAttemptAdapter(user, action, params.deviceFingerprint)

        UserKnownDeviceAdapter userKnownDeviceAdapter = new UserKnownDeviceAdapter(deviceUserApiKeyId, params.deviceFingerprint)
        userKnownDeviceAdapter.channel = loginAttemptAdapter.origin.toString()
        loginAttemptService.processAuthenticationEventRequest(loginAttemptAdapter, userKnownDeviceAdapter)

        if (loginAttemptAdapter.channel.isAsaas() && loginAttemptAdapter.platform.isMobilePlatform() && loginAttemptAdapter.action.isAuthenticate()) {
            hubspotEventService.trackCustomerHasDownloadedApp(user.customer)
        }

        if (params.mobileToken) {
            userMobileDeviceService.save(user, params.mobileToken, ApiMobileUtils.getMobileAppPlatform(), ApiMobileUtils.getApplicationType())
        }

        userTrustedDeviceService.saveRecalculateUserTrustedDeviceExpirationDateAsyncAction(user.id, params.distinctId)
    }

    private void drawHorizontalPaymentWizardAbTestIfNecessary(Customer customer) {
        if (ApiMobileUtils.getApplicationType().isMoney()) return

        abTestService.chooseVariant(grailsApplication.config.asaas.abtests.horizontalPaymentWizard.name, customer, AbTestPlatform.ASAAS_APP)
    }

    private void drawAllowPaymentCreationWithoutDocumentSentAbTestIfNecessary(Customer customer) {
        if (ApiMobileUtils.getApplicationType().isMoney()) return

        abTestService.chooseVariant(grailsApplication.config.asaas.abtests.mobile.allowPaymentCreationWithoutDocumentSent.name, customer, AbTestPlatform.ASAAS_APP)
    }
}
