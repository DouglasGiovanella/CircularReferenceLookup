package com.asaas.service.api

import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiUserMobileDeviceParser
import com.asaas.domain.usermobiledevice.UserMobileDevice
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class ApiUserMobileDeviceService extends ApiBaseService {

    def userMobileDeviceService
    def apiResponseBuilderService

    def save(params) {
        if (!params.token) return [success: true]

        UserMobileDevice userMobileDevice = userMobileDeviceService.save(UserUtils.getCurrentUser(), params.token, ApiMobileUtils.getMobileAppPlatform(), ApiMobileUtils.getApplicationType())

        return apiResponseBuilderService.buildSuccess(ApiUserMobileDeviceParser.buildResponseItem(userMobileDevice))
    }

    def delete(params) {
        Long userMobileDeviceId = userMobileDeviceService.delete(params.token)

        if (!userMobileDeviceId) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildDeleted(userMobileDeviceId)
    }

}
