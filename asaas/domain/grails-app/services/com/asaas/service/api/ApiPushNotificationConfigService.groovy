package com.asaas.service.api

import com.asaas.api.ApiPushNotificationConfigParser
import com.asaas.domain.pushnotification.PushNotificationConfig

import grails.transaction.Transactional

@Transactional
class ApiPushNotificationConfigService extends ApiBaseService {

    def apiResponseBuilderService
    def pushNotificationConfigService

    public Map find(Map params) {
        PushNotificationConfig pushNotificationConfig = PushNotificationConfig.query([providerId: getProvider(params), publicId: params.id]).get()

        if (!pushNotificationConfig) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiPushNotificationConfigParser.buildResponseItem(pushNotificationConfig))
    }

    public Map list(Map params) {
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)
        Map filters = ApiPushNotificationConfigParser.parseFilters(params)
        filters.providerId = getProvider(params)

        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfig.query(filters).list(max: limit, offset: offset, readOnly: true)

        List<Map> responseItems = pushNotificationConfigList.collect { ApiPushNotificationConfigParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(responseItems, limit, offset, pushNotificationConfigList.totalCount)
    }

    public Map save(Map params) {
        Map fields = ApiPushNotificationConfigParser.parseRequestParams(params)

        PushNotificationConfig pushNotificationConfig = pushNotificationConfigService.save(getProviderInstance(params), fields)

        if (pushNotificationConfig.hasErrors()) return apiResponseBuilderService.buildErrorList(pushNotificationConfig)

        return apiResponseBuilderService.buildSuccess(ApiPushNotificationConfigParser.buildResponseItem(pushNotificationConfig))
    }

    public Map update(Map params) {
        Map fields = ApiPushNotificationConfigParser.parseRequestParams(params)

        PushNotificationConfig pushNotificationConfig = PushNotificationConfig.query([providerId: getProvider(params), publicId: fields.publicId]).get()
        if (!pushNotificationConfig) return apiResponseBuilderService.buildNotFoundItem()

        pushNotificationConfig = pushNotificationConfigService.update(pushNotificationConfig, fields)

        if (pushNotificationConfig.hasErrors()) return apiResponseBuilderService.buildErrorList(pushNotificationConfig)

        return apiResponseBuilderService.buildSuccess(ApiPushNotificationConfigParser.buildResponseItem(pushNotificationConfig))
    }

    public Map delete(Map params) {
        PushNotificationConfig pushNotificationConfig = PushNotificationConfig.query([providerId: getProvider(params), publicId: params.id]).get()
        if (!pushNotificationConfig) return apiResponseBuilderService.buildNotFoundItem()

        pushNotificationConfigService.delete(pushNotificationConfig)

        return apiResponseBuilderService.buildDeleted(pushNotificationConfig.publicId)
    }
}
