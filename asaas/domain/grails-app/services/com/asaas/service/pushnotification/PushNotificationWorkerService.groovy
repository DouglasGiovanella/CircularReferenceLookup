package com.asaas.service.pushnotification

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationSendType
import com.asaas.log.AsaasLogger
import com.asaas.namedqueries.NamedQueries
import com.asaas.namedqueries.SqlOrder
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.pushnotification.PushNotificationUtils
import com.asaas.pushnotification.worker.PushNotificationWorkerConfigVO
import com.asaas.redis.RedissonProxy

import grails.transaction.Transactional

import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy
import org.hibernate.impl.CriteriaImpl

@Transactional
class PushNotificationWorkerService {

    def pushNotificationConfigWithPendingRequestCacheService

    public List<Long> listPushNotificationConfigIdListToBeProcessed(PushNotificationWorkerConfigVO pushNotificationWorkerConfig, List<Long> pushNotificationConfigIdListProcessing, Map search) {
        if (PushNotificationUtils.existsConfigIdRepeteadInList(pushNotificationConfigIdListProcessing)) {
            AsaasLogger.warn("PushNotificationWorkerService.listPushNotificationConfigIdListToBeProcessed >> Existem elementos repetidos na lista pushNotificationConfigIdListProcessing")
        }

        Boolean isRedisEnabled = RedissonProxy.instance.isConnected()

        if (isRedisEnabled) {
            List<Long> cachedConfigIdList = listCachedPushNotificationConfigIdList(pushNotificationWorkerConfig.eventList, pushNotificationConfigIdListProcessing)
            if (!cachedConfigIdList) return []

            search."id[in]" = cachedConfigIdList
        } else if (pushNotificationConfigIdListProcessing) {
            search."id[notIn]" = pushNotificationConfigIdListProcessing
        }

        return listPushNotificationConfigWithPendingRequestsIdList(pushNotificationWorkerConfig.eventList, pushNotificationWorkerConfig.sendType, search)
    }

    private List<Long> listCachedPushNotificationConfigIdList(List<PushNotificationRequestEvent> eventList, List<Long> pushNotificationConfigIdListProcessing) {
        List<Long> cachedConfigIdList = pushNotificationConfigWithPendingRequestCacheService.list(eventList)

        if (PushNotificationUtils.existsConfigIdRepeteadInList(cachedConfigIdList)) {
            AsaasLogger.warn("PushNotificationWorkerService.listCachedPushNotificationConfigIdList >> Existem elementos repetidos na lista cachedConfigIdList")
        }

        if (pushNotificationConfigIdListProcessing) {
            cachedConfigIdList = cachedConfigIdList - pushNotificationConfigIdListProcessing
        }

        return cachedConfigIdList
    }

    private List<Long> listPushNotificationConfigWithPendingRequestsIdList(List<PushNotificationRequestEvent> eventList, PushNotificationSendType sendType, Map search) {
        Map withPendingRequestsQuery = search

        withPendingRequestsQuery.column = "id"
        withPendingRequestsQuery.anyApplication = true
        withPendingRequestsQuery."pushNotificationRequestEvent[in]" = eventList
        withPendingRequestsQuery.sendType = sendType

        NamedCriteriaProxy criteriaProxy = PushNotificationConfig.withPendingRequests(withPendingRequestsQuery)

        CriteriaImpl criteria = NamedQueries.buildCriteriaFromNamedQuery(criteriaProxy)
        criteria.addOrder(new SqlOrder("(SELECT MIN(pnr_.id) FROM push_notification_request pnr_ WHERE pnr_.config_id = this_.id and pnr_.sent = false and pnr_.deleted = false and pnr_.event in ('${eventList.join("','")}'))"))
        criteria.setReadOnly(true)

        List<Long> pushNotificationConfigWithPendingRequestsIdList = criteria.list()

        if (PushNotificationUtils.existsConfigIdRepeteadInList(pushNotificationConfigWithPendingRequestsIdList)) {
            AsaasLogger.warn("PushNotificationWorkerService.listPushNotificationConfigWithPendingRequestsIdList >> Existem elementos repetidos na lista pushNotificationConfigWithPendingRequestsIdList")
        }

        return pushNotificationConfigWithPendingRequestsIdList
    }
}
