package com.asaas.service.pushnotification

import com.asaas.cache.RedisCache
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.redisson.api.RBucket

import java.util.concurrent.TimeUnit

import org.redisson.api.RList
import org.redisson.api.RSetCache
import org.redisson.api.RedissonClient

@Transactional
class PushNotificationConfigWithPendingRequestCacheService {

    private static final Integer INITIAL_EVENT_TIME_TO_LIVE_IN_MINUTES = 60
    private static final Integer EVICT_EVENT_TIME_TO_LIVE_IN_SECONDS = 30

    public void checkCacheAndSaveMissingConfigListIfNecessary() {
        if (!RedissonProxy.instance.isConnected()) return

        Map search = [column: "id", anyApplication: true]
        List<Long> cachedConfigIdList = list(PushNotificationRequestEvent.values().toList())
        if (cachedConfigIdList) {
            search."id[notIn]" = cachedConfigIdList
        }

        List<Long> configIdList = PushNotificationConfig.withPendingRequests(search).list()
        if (configIdList) {
            AsaasLogger.warn("PushNotificationConfigWithPendingRequestCacheService.checkCacheAndSaveMissingConfigListIfNecessary >> Configs com notificação que não estavam no cache: ${configIdList}")

            List<Map> configIdWithEventMapList = PushNotificationRequest.notSentGroupedByConfigIdAndEvent(["configId[in]": configIdList]).list()

            for (Map configIdWithEventMap : configIdWithEventMapList) {
                save(configIdWithEventMap."config.id", configIdWithEventMap.event)
            }
        }
    }

    public void save(Long configId, PushNotificationRequestEvent event) {
        try {
            if (!RedissonProxy.instance.isConnected()) return

            RedissonClient redisson = RedissonProxy.instance.getClient()

            RSetCache set = redisson.getSetCache(buildEventKey(event))
            set.add(configId, INITIAL_EVENT_TIME_TO_LIVE_IN_MINUTES, TimeUnit.MINUTES)
        } catch (Exception e) {
            AsaasLogger.error("PushNotificationConfigWithPendingRequestCacheService.save >> Erro ao tentar salvar [${event}] para a config ${configId}", e)
        }
    }

    public List<Long> list(List<PushNotificationRequestEvent> events) {
        try {
            if (!RedissonProxy.instance.isConnected()) return []

            RedissonClient redisson = RedissonProxy.instance.getClient()

            Set<Long> configIdList = []
            for (PushNotificationRequestEvent event : events) {
                List<Long> configs = redisson.getSetCache(buildEventKey(event)).toArray()
                configIdList.addAll(configs)
            }

            return configIdList.toList()
        } catch (Exception e) {
            AsaasLogger.error("PushNotificationConfigWithPendingRequestCacheService.list >> Erro ao tentar listar configs para os eventos [${events}]", e)
            return []
        }
    }

    public void decreaseTtl(Long configId) {
        if (!RedissonProxy.instance.isConnected()) return

        List<PushNotificationRequestEvent> pushNotificationConfigEventList = PushNotificationConfigEvent.query([configId: configId, column: "event"]).list()

        RedissonClient redisson = RedissonProxy.instance.getClient()

        for (PushNotificationRequestEvent event : pushNotificationConfigEventList) {
            try {
                RSetCache set = redisson.getSetCache(buildEventKey(event))

                if (set.contains(configId)) {
                    set.add(configId, EVICT_EVENT_TIME_TO_LIVE_IN_SECONDS, TimeUnit.SECONDS)
                }
            } catch (Exception e) {
                AsaasLogger.error("PushNotificationConfigWithPendingRequestCacheService.decreaseTtl >> Erro ao tentar remover configId da lista de pendentes", e)
            }
        }
    }

    public void resetTtl(Long configId) {
        if (!RedissonProxy.instance.isConnected()) return

        final Integer offsetToResetTtlInMinutes = INITIAL_EVENT_TIME_TO_LIVE_IN_MINUTES / 2
        try {
            RBucket<Long> bucket = RedissonProxy.instance.getBucket("PushNotificationConfigWithRequestLastTtlReset:${configId}", Long)

            if (bucket.isExists() && CustomDateUtils.sumMinutes(new Date(bucket.get()), offsetToResetTtlInMinutes).after(new Date())) return

            List<Map> configIdWithEventMapList = PushNotificationRequest.notSentGroupedByConfigIdAndEvent(["configId": configId]).list()
            for (Map configIdWithEventMap : configIdWithEventMapList) {
                save(configId, configIdWithEventMap.event)
            }

            bucket.set(new Date().time)
        } catch (Exception e) {
            AsaasLogger.error("PushNotificationConfigWithPendingRequestCacheService.resetTtl >> Erro ao tentar resetar configId da lista de pendentes", e)
        }
    }

    private String buildEventKey(PushNotificationRequestEvent event) {
        return "${RedisCache.KEY_PREFIX}:PushNotificationConfigEventWithConfigList:${event}"
    }
}
