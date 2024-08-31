package com.asaas.service.login

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.user.User
import com.asaas.domain.user.UserCookie
import com.asaas.user.UserUtils
import com.asaas.utils.RequestUtils
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder

@Transactional
class UserCookieService {

    public UserCookie createUserCookieIfNecessary() {
        if (RequestContextHolder.getRequestAttributes()?.session.reauthenticatedAsProvider) return null

        User currentUser = UserUtils.getCurrentUser()
        if (!currentUser) return null

        String cookieName = AsaasApplicationHolder.config.asaas.cookies.browserId.name
        String cookieValue = RequestUtils.getCookieValue(cookieName)

        if (!cookieValue) return null

        UserCookie existingUserCookie = UserCookie.query(user: currentUser, value: cookieValue).get()
        if (existingUserCookie) return existingUserCookie

        return save(currentUser, cookieValue)
    }

    private UserCookie save(User user, String value) {
        UserCookie userCookie = new UserCookie(user: user, value: value, customer: user.customer)
        userCookie.save(failOnError: true)
        return userCookie
    }

}
