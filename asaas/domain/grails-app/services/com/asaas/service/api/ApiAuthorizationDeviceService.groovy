package com.asaas.service.api

import com.asaas.api.ApiAuthorizationDeviceParser
import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.authorizationdevice.AuthorizationDeviceVO
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.login.UserKnownDevice
import com.asaas.user.UserUtils
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.utils.UserKnownDeviceUtils
import grails.transaction.Transactional

@Transactional
class ApiAuthorizationDeviceService extends ApiBaseService {

	def apiResponseBuilderService
    def mobileAppTokenService
    def smsTokenService

	def find(params) {
		Map responseItem = [:]

		AuthorizationDevice activeDevice = AuthorizationDevice.active([customer: getProviderInstance(params)]).get()
		AuthorizationDevice pendingDevice = AuthorizationDevice.pending([customer: getProviderInstance(params), type: AuthorizationDeviceType.SMS_TOKEN]).get()

		responseItem.activeDevice = activeDevice ? ApiAuthorizationDeviceParser.buildResponseItem(activeDevice) : null
		responseItem.pendingDevice = pendingDevice ? ApiAuthorizationDeviceParser.buildResponseItem(pendingDevice) : null

		return responseItem
	}

    def activateSmsDevice(params) {
        AuthorizationDeviceVO authorizationDeviceVO = smsTokenService.requestActivate(getProviderInstance(params), params.token, buildUserDeviceSecurityVO(true))
    	return apiResponseBuilderService.buildSuccess(authorizationDeviceVO.toMap())
    }

    def unlockMobileAppToken(params) {
        AuthorizationDevice authorizationDevice = mobileAppTokenService.unlock(getProviderInstance(params), params.token, buildUserDeviceSecurityVO(false))

        Map responseMap = ApiAuthorizationDeviceParser.buildResponseItem(authorizationDevice)
        responseMap.secretKey = mobileAppTokenService.encryptSecretKey(authorizationDevice)

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    private UserDeviceSecurityVO buildUserDeviceSecurityVO(Boolean shouldCheckTrust) {
        UserKnownDevice currentDevice = UserKnownDeviceUtils.getCurrentDevice(UserUtils.getCurrentUser().id)
        return new UserDeviceSecurityVO(currentDevice, shouldCheckTrust)
    }
}
