package com.asaas.service.pushnotification.cache

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.pushnotification.cache.PushNotificationConfigVO

import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class PushNotificationConfigCacheService {

    def grailsCacheManager

    @Cacheable(value = "PushNotificationConfigCache:byId")
    public PushNotificationConfigVO get(Long id) {
        if (!id) return null

        PushNotificationConfig pushNotificationConfig = PushNotificationConfig.read(id)
        if (!pushNotificationConfig) return null

        return PushNotificationConfigVO.build(pushNotificationConfig)
    }

    public void evict(Long id) {
        grailsCacheManager.getCache("PushNotificationConfigCache:byId").evict(id)
    }
}
