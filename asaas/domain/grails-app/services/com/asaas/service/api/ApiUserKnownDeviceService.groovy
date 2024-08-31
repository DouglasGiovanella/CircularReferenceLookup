package com.asaas.service.api

import com.asaas.api.ApiUserKnownDeviceParser
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiUserKnownDeviceService extends ApiBaseService {

    def apiResponseBuilderService
    def loginSessionService

    public Map list(Map params) {
        User user = UserUtils.getCurrentUser()

        Map filters = ApiUserKnownDeviceParser.parseListFilters(params)

        List<UserKnownDevice> userKnownDevices = UserKnownDevice.query(filters + [user: user, sort: "lastUpdated", order: "desc"]).list(max: getLimit(params), offset: getOffset(params), readOnly: true)
        List<Map> responseItems = userKnownDevices.collect { userKnownDevice -> ApiUserKnownDeviceParser.buildResponseItem(userKnownDevice) }

        return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), userKnownDevices.totalCount)
    }

    public Map invalidate(Map params) {
        loginSessionService.invalidateLoginSession(UserUtils.getCurrentUserId(), Utils.toLong(params.id))

        return apiResponseBuilderService.buildSuccess([success: true])
    }

}
