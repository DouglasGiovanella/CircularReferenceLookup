package com.asaas.service.api

import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiCriticalActionConfigParser
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.login.UserKnownDevice
import com.asaas.user.UserUtils
import com.asaas.utils.UserKnownDeviceUtils
import grails.transaction.Transactional

@Transactional
class ApiCriticalActionConfigService extends ApiBaseService {

    def apiCriticalActionService
	def criticalActionConfigService

	def find(params) {
        Customer customer = getProviderInstance(params)
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()

        Map responseMap = ApiCriticalActionConfigParser.buildResponseItem(customer, customerCriticalActionConfig)

		return responseMap
	}

    def update(params) {
        if (!ApiMobileUtils.isMobileAppRequest()) {
            params.allowDisableCheckoutCriticalAction = true
        }

        Customer customer = getProviderInstance(params)
        UserKnownDevice currentUserKnownDevice = UserKnownDeviceUtils.getCurrentDevice(UserUtils.getCurrentUser()?.id)
        CustomerCriticalActionConfig customerCriticalActionConfig = criticalActionConfigService.update(customer, params, currentUserKnownDevice)

        Map responseMap = ApiCriticalActionConfigParser.buildResponseItem(customer, customerCriticalActionConfig)
        responseMap.criticalActions = apiCriticalActionService.list(params + [typeList: CriticalActionType.getUserConfigList()])

        return responseMap
    }
}
