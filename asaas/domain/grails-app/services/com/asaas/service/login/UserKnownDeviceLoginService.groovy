package com.asaas.service.login

import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.login.UserKnownDeviceLogin
import com.asaas.iplocation.IpLocationAdapter
import com.asaas.login.UserKnownDeviceAdapter
import grails.transaction.Transactional

@Transactional
class UserKnownDeviceLoginService {

    public void saveUserKnownDeviceLogin(UserKnownDevice userKnownDevice, UserKnownDeviceAdapter userKnownDeviceAdapter) {
        UserKnownDeviceLogin userKnownDeviceLogin = new UserKnownDeviceLogin()

        userKnownDeviceLogin.user = userKnownDevice.user
        userKnownDeviceLogin.userKnownDevice = userKnownDevice
        userKnownDeviceLogin.remoteIp = userKnownDeviceAdapter?.remoteIp ?: userKnownDevice.remoteIp
        userKnownDeviceLogin.sourcePort = userKnownDeviceAdapter?.sourcePort ?: userKnownDevice.sourcePort
        userKnownDeviceLogin.deviceFingerprint = userKnownDeviceAdapter?.deviceFingerprint ?: userKnownDevice.deviceFingerprint

        IpLocationAdapter ipLocationAdapter = userKnownDeviceAdapter?.ipLocationAdapter
        if (ipLocationAdapter) {
            userKnownDeviceLogin.city = ipLocationAdapter.city
            userKnownDeviceLogin.state = ipLocationAdapter.state
            userKnownDeviceLogin.country = ipLocationAdapter.country
        } else {
            userKnownDeviceLogin.city = userKnownDevice.city
            userKnownDeviceLogin.state = userKnownDevice.state
            userKnownDeviceLogin.country = userKnownDevice.country
        }

        userKnownDeviceLogin.save(failOnError: true)
    }
}
