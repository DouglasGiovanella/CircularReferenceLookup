package com.asaas.service.login

import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import grails.transaction.Transactional
import org.springframework.security.core.Authentication
import org.springframework.security.web.authentication.logout.LogoutHandler

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Transactional
class UserKnownDeviceLogoutHandlerService implements LogoutHandler {

    def userKnownDeviceService

    @Override
    void logout(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Authentication authentication) {
        User user = UserUtils.getCurrentUser()
        if (user) userKnownDeviceService.deactivateCurrent(user.id)
    }
}
