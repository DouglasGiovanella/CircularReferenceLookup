package com.asaas.service.api

import com.asaas.api.ApiWebhookParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigApplication
import com.asaas.domain.pushnotification.PushNotificationType
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiWebhookService extends ApiBaseService {

	def apiResponseBuilderService
	def pushNotificationConfigLegacyService

	public Map find(Map params, PushNotificationType pushNotificationType) {
 		Map search = [provider: getProviderInstance(params), type: pushNotificationType]
 		if (params.application) search.application = PushNotificationConfigApplication.convert(params.application)

 		PushNotificationConfig pushNotificationConfig = PushNotificationConfig.query(search).get()

 		if (!pushNotificationConfig) return apiResponseBuilderService.buildNotFoundItem()

 		return apiResponseBuilderService.buildSuccess(ApiWebhookParser.buildResponseItem(pushNotificationConfig))
	}

    public Map save(params, PushNotificationType pushNotificationType) {
        Customer customer = getProviderInstance(params)

        Map parsedParams = ApiWebhookParser.parseSaveParams(params + [type:  pushNotificationType])

        PushNotificationConfig pushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(customer, parsedParams)

        if (pushNotificationConfig.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pushNotificationConfig)
        }

    	return apiResponseBuilderService.buildSuccess(ApiWebhookParser.buildResponseItem(pushNotificationConfig))
    }

    public Map savePlugaConfig(Map params) {
		Customer customer = getProviderInstance(params)

 		PushNotificationConfig pushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(customer, buildPlugaConfig(customer, params))
        if (pushNotificationConfig.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pushNotificationConfig)
        }

 		return apiResponseBuilderService.buildSuccess(ApiWebhookParser.buildResponseItem(pushNotificationConfig))
	}

 	private Map buildPlugaConfig(Customer customer, Map params) {
		Map pushNotificationConfig = [:]
		pushNotificationConfig.provider = customer
		pushNotificationConfig.application = Utils.toBoolean(params.isSecondaryConfig) ? PushNotificationConfigApplication.PLUGA_V2 : PushNotificationConfigApplication.PLUGA
		pushNotificationConfig.type = PushNotificationType.PAYMENT
		pushNotificationConfig.poolInterrupted = false
		pushNotificationConfig.email = customer.email
		pushNotificationConfig.apiVersion = 3
		pushNotificationConfig.url = params.url
		pushNotificationConfig.enabled = true

 		return pushNotificationConfig
	}
}
