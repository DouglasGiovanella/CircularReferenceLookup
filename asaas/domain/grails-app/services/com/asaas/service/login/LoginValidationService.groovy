package com.asaas.service.login

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.login.LoginAttemptFailure
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.login.LoginAttemptAdapter
import com.asaas.login.LoginValidationResult
import com.asaas.login.LoginExtraValidationType
import com.asaas.login.thirdpartyloginvalidation.ThirdPartyLoginValidationAdapter
import com.asaas.login.thirdpartyloginvalidation.ThirdPartyLoginValidationResult
import com.asaas.login.vo.LoginExtraValidationVO
import com.asaas.user.UserPasswordValidator
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class LoginValidationService {

    private final static String TRACK_NAME_LOGIN_AUTHENTICATION_FAILURE = "login_authentication_failure"

    def asaasSegmentioService
    def loginAttemptService
    def loginConfigCacheService
    def loginLinkValidationRequestService
    def loginUnlockRequestService
    def mfaService
    def thirdPartyLoginValidationSqsMessageService

    public User validateLogin(String username, String password, LoginAttemptAdapter loginAttemptAdapter, Boolean bypassExpiredPasswordValidation) {
        if (!username || !password) {
            saveFailureAttemptIfPossible(loginAttemptAdapter)
            return DomainUtils.addErrorWithErrorCode(new User(), LoginValidationResult.INVALID_USERNAME_OR_PASSWORD.errorCode, Utils.getMessageProperty("springSecurity.errors.login.fail"))
        }

        User user = User.query([username: username, ignoreCustomer: true]).get()
        if (!user) {
            saveFailureAttemptIfPossible(loginAttemptAdapter)
            trackLoginAuthenticationFailureFromUsername(username, "user_not_found")
            return DomainUtils.addErrorWithErrorCode(new User(), LoginValidationResult.INVALID_USERNAME_OR_PASSWORD.errorCode, Utils.getMessageProperty("springSecurity.errors.login.fail"))
        }

        if (!UserPasswordValidator.isPasswordValid(user, password)) {
            saveFailureAttemptIfPossible(loginAttemptAdapter)
            trackLoginAuthenticationFailure(user, "invalid_password")
            return DomainUtils.addErrorWithErrorCode(user, LoginValidationResult.INVALID_USERNAME_OR_PASSWORD.errorCode, Utils.getMessageProperty("springSecurity.errors.login.fail"))
        }

        if (user.isDisabled()) {
            saveFailureAttemptIfPossible(loginAttemptAdapter)
            trackLoginAuthenticationFailure(user, "user_disabled")
            return DomainUtils.addErrorWithErrorCode(user, LoginValidationResult.INVALID_USERNAME_OR_PASSWORD.errorCode, Utils.getMessageProperty("springSecurity.errors.login.fail"))
        }

        if (user.customer.getIsBlocked()) {
            saveBlockedAttemptIfPossible(loginAttemptAdapter)
            trackLoginAuthenticationFailure(user, "account_blocked")
            return DomainUtils.addErrorWithErrorCode(user, LoginValidationResult.ACCOUNT_BLOCKED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.locked"))
        }

        if (user.passwordExpired && !bypassExpiredPasswordValidation) {
            saveFailureAttemptIfPossible(loginAttemptAdapter)
            trackLoginAuthenticationFailure(user, "password_expired")
            return DomainUtils.addErrorWithErrorCode(user, LoginValidationResult.PASSWORD_EXPIRED.errorCode, Utils.getMessageProperty("springSecurity.errors.login.passwordExpired"))
        }

        return user
    }

    public Boolean verifyAndBlockIpIfNecessary(String remoteIp) {
        final List<String> asaasHeadquarterRemoteIpList = [AsaasApplicationHolder.config.asaas.hq.remoteIp,
                                                           AsaasApplicationHolder.config.asaas.hq.fallbackRemoteIp
        ]
        if (asaasHeadquarterRemoteIpList.contains(remoteIp)) return false

        if (shouldBlockIp(remoteIp)) {
            AsaasLogger.warn("LoginValidationService.verifyAndBlockIpIfNecessary >> IP bloqueado no ASAAS por abuso de Login, remoteIp: ${remoteIp}.")

            return false
        }

        return false
    }

    public LoginExtraValidationVO checkUserNeedsExtraValidation(String username, String browserId, ThirdPartyLoginValidationAdapter thirdPartyLoginValidationAdapter) {
        User user = User.query([username: username, ignoreCustomer: true]).get()
        thirdPartyLoginValidationSqsMessageService.saveSqsMessage(thirdPartyLoginValidationAdapter.toMap())

        Boolean userNeedsLoginLinkValidation = loginLinkValidationRequestService.userNeedsLoginLinkValidation(user.id, browserId, true)
        if (userNeedsLoginLinkValidation) {
            if (shouldBypassMfaBasedOnThirdPartyLoginValidation(user.username, thirdPartyLoginValidationAdapter.result)) {
                trackUserBypassedMfa(user, thirdPartyLoginValidationAdapter.authScore, LoginExtraValidationType.MFA_LOGIN_LINK_VALIDATION_REQUEST)
                return new LoginExtraValidationVO(false, null)
            }
            trackUserNeedsMfa(user, LoginExtraValidationType.MFA_LOGIN_LINK_VALIDATION_REQUEST)
            return new LoginExtraValidationVO(true, LoginExtraValidationType.MFA_LOGIN_LINK_VALIDATION_REQUEST)
        }

        Boolean userNeedsMfaCodeValidation = mfaService.userNeedsMfaCodeValidation(user.id, browserId)
        if (userNeedsMfaCodeValidation) {
            if (shouldBypassMfaBasedOnThirdPartyLoginValidation(user.username, thirdPartyLoginValidationAdapter.result)) {
                trackUserBypassedMfa(user, thirdPartyLoginValidationAdapter.authScore, LoginExtraValidationType.MFA_CODE_VALIDATION)
                return new LoginExtraValidationVO(false, null)
            }
            trackUserNeedsMfa(user, LoginExtraValidationType.MFA_CODE_VALIDATION)
            return new LoginExtraValidationVO(true, LoginExtraValidationType.MFA_CODE_VALIDATION)
        }

        if (loginUnlockRequestService.userNeedsUnlockLogin(user.username)) {
            return new LoginExtraValidationVO(true, LoginExtraValidationType.FACEMATCH_UNLOCK)
        }

        trackUserDoesntNeedsMfa(user, thirdPartyLoginValidationAdapter)
        return new LoginExtraValidationVO(false, null)
    }

    public void trackLoginAuthenticationFailureFromUsername(String username, String reason) {
        if (!username) return

        Map trackInfo = [:]
        trackInfo.username = username
        trackInfo.reason = reason

        asaasSegmentioService.track(null, TRACK_NAME_LOGIN_AUTHENTICATION_FAILURE, trackInfo)
    }

    public void trackLoginAuthenticationFailure(User user, String reason) {
        if (!user) return

        Map trackInfo = [:]
        trackInfo.userId = user.id
        trackInfo.username = user.username
        trackInfo.reason = reason

        asaasSegmentioService.track(user.customerId, TRACK_NAME_LOGIN_AUTHENTICATION_FAILURE, trackInfo)
    }

    private void saveBlockedAttemptIfPossible(LoginAttemptAdapter loginAttemptAdapter) {
        if (!loginAttemptAdapter) return
        loginAttemptService.saveBlocked(loginAttemptAdapter)
    }

    private void saveFailureAttemptIfPossible(LoginAttemptAdapter loginAttemptAdapter) {
        if (!loginAttemptAdapter) return
        loginAttemptService.saveFailure(loginAttemptAdapter)
    }

    private Boolean shouldBlockIp(String searcheableIp) {
        if (unsuccessfulAttemptsInLastHalfMinute(searcheableIp) > 20) return true
        if (unsuccessfulAttemptsInLastTwelveHours(searcheableIp) > 120) return true
        if (unsuccessfulUsernameAttemptsInLastTwelveHours(searcheableIp) > 100) return true

        return false
    }

    private Integer unsuccessfulAttemptsInLastHalfMinute(String searcheableIp) {
        Date startDate = CustomDateUtils.sumSeconds(new Date(), 30 * -1)

        return LoginAttemptFailure.recentAttempts(startDate, ["remoteIp": searcheableIp]).count()
    }

    private Integer unsuccessfulAttemptsInLastTwelveHours(String searcheableIp) {
        Date startDate = CustomDateUtils.sumHours(new Date(), 12 * -1)

        return LoginAttemptFailure.recentAttempts(startDate, ["remoteIp": searcheableIp]).count()
    }

    private Integer unsuccessfulUsernameAttemptsInLastTwelveHours(String searcheableIp) {
        Date startDate = CustomDateUtils.sumHours(new Date(), 12 * -1)

        return LoginAttemptFailure.recentAttempts(
                startDate,
                [countDistinct: "username", "remoteIp": searcheableIp]
        ).get()
    }

    private Boolean shouldBypassMfaBasedOnThirdPartyLoginValidation(String username, ThirdPartyLoginValidationResult result) {
        if (UserUtils.hasAsaasEmail(username)) return false

        if (!result) return false

        Boolean enabledThirdPartyLoginValidation = loginConfigCacheService.getThirdPartyLoginValidationConfig().enabledThirdPartyLoginValidation
        if (!enabledThirdPartyLoginValidation) return false

        return result.isPass()
    }

    private void trackUserBypassedMfa(User user, String authScore, LoginExtraValidationType loginExtraValidationType) {
        AsaasLogger.info("LoginValidationService.trackUserBypassedMfa >> User [${user.id}] recebeu Bypass com base na validação de login de terceiros. Username [${user.username}] AuthScore [${authScore}]")

        Map trackInfo = [:]
        trackInfo.action = "bypass_based_on_third_party_login_validation"
        trackInfo.userId = user.id
        trackInfo.mfaCheckType = loginExtraValidationType.toString()
        asaasSegmentioService.track(user.customerId, "check_user_needs_mfa", trackInfo)
    }

    private void trackUserDoesntNeedsMfa(User user, ThirdPartyLoginValidationAdapter thirdPartyLoginValidationAdapter) {
        Map trackInfo = [:]
        trackInfo.action = "unsolicited_mfa"
        trackInfo.userId = user.id
        trackInfo.thirdPartyLoginValidationResult = thirdPartyLoginValidationAdapter?.result.toString()
        asaasSegmentioService.track(user.customerId, "check_user_needs_mfa", trackInfo)
    }

    private void trackUserNeedsMfa(User user, LoginExtraValidationType loginExtraValidationType) {
        Map trackInfo = [:]
        trackInfo.action = "solicited_mfa"
        trackInfo.userId = user.id
        trackInfo.mfaCheckType = loginExtraValidationType.toString()
        asaasSegmentioService.track(user.customerId, "check_user_needs_mfa", trackInfo)
    }
}
