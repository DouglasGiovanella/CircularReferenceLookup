package com.asaas.service.login

import com.asaas.domain.login.LoginAttempt
import com.asaas.domain.login.LoginAttemptFailure
import com.asaas.domain.login.UserTemporaryBlock
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.login.LoginAttemptAdapter
import com.asaas.login.LoginAttemptOrigin
import com.asaas.login.UserKnownDeviceAdapter
import com.asaas.userTemporaryBlock.UserTemporaryBlockRepository
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.RequestUtils
import grails.transaction.Transactional
import org.apache.catalina.session.StandardSessionFacade
import org.springframework.web.context.request.RequestContextHolder

import javax.servlet.http.HttpServletRequest

@Transactional
class LoginAttemptService {

    public static final Integer TOO_MANY_ATTEMPTS_BLOCK_MINUTES = 30

    def connectedAccountInfoHandlerService
    def userKnownDeviceService

    public void saveSuccess(LoginAttemptAdapter loginAttemptAdapter) {
        loginAttemptAdapter.success = true
        save(loginAttemptAdapter)
    }

    public void saveBlocked(LoginAttemptAdapter loginAttemptAdapter) {
        loginAttemptAdapter.blocked = true
        loginAttemptAdapter.success = false
        save(loginAttemptAdapter)
    }

    public void saveFailure(LoginAttemptAdapter loginAttemptAdapter) {
        loginAttemptAdapter.success = false
        save(loginAttemptAdapter)
        saveAttemptFailure(loginAttemptAdapter)
    }

    public void processAuthenticationEventRequest(LoginAttemptAdapter loginAttemptAdapter, UserKnownDeviceAdapter userKnownDeviceAdapter) {
        User user = User.get(loginAttemptAdapter.userId)

        HttpServletRequest request = RequestContextHolder.currentRequestAttributes().getRequest()
        StandardSessionFacade session = request.getSession(false)
        session?.setAttribute("loginSessionVersion", user.loginSessionVersion)

        User.executeUpdate("update User set lastLogin = :now, lastLoginIp = :remoteIp where id = :id", [now: new Date(), remoteIp: userKnownDeviceAdapter.remoteIp, id: user.id])

        saveSuccess(loginAttemptAdapter)

        if (loginAttemptAdapter.origin == LoginAttemptOrigin.LOGIN_PAGE) {
            connectedAccountInfoHandlerService.saveLoginInfoIfPossible(user, loginAttemptAdapter.remoteIp, RequestUtils.getBrowserCookie())
        }

        userKnownDeviceService.saveAsyncActionIfNecessary(user.id, userKnownDeviceAdapter)
    }

    public Boolean verifyTooManyFailedAttemptsAndBlockIfNeeded(String username, Integer maxAttempts) {
        User user = User.activeByUsername(username).get()
        if (!user) return false

        LoginAttempt lastLoginAttempt = LoginAttempt.query([username: username]).get()
        if (!lastLoginAttempt) return false
        if (lastLoginAttempt.success) return false

        Map lastLoginAttemptListSearch = [username: username, order: "desc"]

        Date lastUserTemporaryBlockDate = UserTemporaryBlockRepository.query([username: username])
            .column("blockUntilDate").sort("id", "desc").readOnly().get()

        if (lastUserTemporaryBlockDate){
            lastLoginAttemptListSearch."dateCreated[ge]" = lastUserTemporaryBlockDate
        }

        List<LoginAttempt> lastLoginAttemptList = LoginAttempt.query(lastLoginAttemptListSearch).list(max: maxAttempts)
        if (lastLoginAttemptList.size() < maxAttempts) return false
        if (lastLoginAttemptList.any { it.success }) return false

        Calendar blockUntilDate = CustomDateUtils.getInstanceOfCalendar()
        blockUntilDate.add(Calendar.MINUTE, TOO_MANY_ATTEMPTS_BLOCK_MINUTES)

        UserTemporaryBlock newUserTemporaryBlock = new UserTemporaryBlock()
        newUserTemporaryBlock.blockUntilDate = blockUntilDate.getTime()
        newUserTemporaryBlock.user = user
        newUserTemporaryBlock.save(failOnError: true)

        return true
    }

    private void save(LoginAttemptAdapter loginAttemptAdapter) {
        LoginAttempt loginAttempt = new LoginAttempt()

        loginAttempt.origin = loginAttemptAdapter.origin
        loginAttempt.action = loginAttemptAdapter.action
        loginAttempt.platform = loginAttemptAdapter.platform
        loginAttempt.channel = loginAttemptAdapter.channel

        loginAttempt.success = loginAttemptAdapter.success
        loginAttempt.blocked = loginAttemptAdapter.blocked
        loginAttempt.session = null
        loginAttempt.referer = loginAttemptAdapter.loginReferer
        loginAttempt.remoteIp = loginAttemptAdapter.remoteIp
        loginAttempt.sourcePort = loginAttemptAdapter.sourcePort
        loginAttempt.username = loginAttemptAdapter.username
        loginAttempt.deviceFingerprint = loginAttemptAdapter.deviceFingerprint?.take(255)

        if (loginAttemptAdapter.userId) {
            loginAttempt.user = User.read(loginAttemptAdapter.userId)
            loginAttempt.username = loginAttempt.user.username
        }

        if (!loginAttemptAdapter.success) {
            String headers = RequestUtils.getHeadersString()
            AsaasLogger.warn("Login Attempt Failure: [loginAttemptOrigin: ${loginAttemptAdapter.origin.toString()}, remoteIp: ${loginAttemptAdapter.remoteIp}, username: ${loginAttemptAdapter.username}, headers: ${headers}]")
        }

        loginAttempt.save(failOnError: true)
    }

    private void saveAttemptFailure(LoginAttemptAdapter loginAttemptAdapter) {
        LoginAttemptFailure loginAttemptFailure = new LoginAttemptFailure()

        loginAttemptFailure.username = loginAttemptAdapter.username
        loginAttemptFailure.remoteIp = loginAttemptAdapter.remoteIp
        loginAttemptFailure.sourcePort = loginAttemptAdapter.sourcePort

        loginAttemptFailure.save(failOnError: true)
    }
}
