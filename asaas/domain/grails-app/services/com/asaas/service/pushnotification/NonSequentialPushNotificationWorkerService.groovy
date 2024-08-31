package com.asaas.service.pushnotification

import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.pushnotification.cache.PushNotificationConfigVO
import com.asaas.pushnotification.worker.PushNotificationWorkerConfigVO
import com.asaas.pushnotification.worker.nonsequential.NonSequentialPushNotificationWorkerItemVO

import grails.transaction.Transactional

@Transactional
class NonSequentialPushNotificationWorkerService {

    def pushNotificationConfigCacheService
    def pushNotificationWorkerService

    public List<NonSequentialPushNotificationWorkerItemVO> listItemsToBeProcessed(PushNotificationWorkerConfigVO pushNotificationWorkerConfig, List<Long> configUsingMaxThreadsAllowedIdList, List<Long> requestIdListProcessing, Integer availableThreads) {
        List<Long> configIdList = pushNotificationWorkerService.listPushNotificationConfigIdListToBeProcessed(pushNotificationWorkerConfig, configUsingMaxThreadsAllowedIdList, [limit: availableThreads])

        return buildItemList(configIdList, requestIdListProcessing, pushNotificationWorkerConfig.maxPushNotificationRequestPerThread)
    }

    private List<NonSequentialPushNotificationWorkerItemVO> buildItemList(List<Long> configIdList, List<Long> requestIdListProcessing, Integer maxPushNotificationRequestPerThread) {
        List<NonSequentialPushNotificationWorkerItemVO> itemList = []

        for (Long configId : configIdList) {
            PushNotificationConfigVO pushNotificationConfig = pushNotificationConfigCacheService.get(configId)

            Map search = [configId: configId, column: "id", disableSort: true]
            if (requestIdListProcessing) {
                search."id[notIn]" = requestIdListProcessing
            }

            List<Long> requestIdList = PushNotificationRequest.readyToSend(search).list(max: maxPushNotificationRequestPerThread)
            if (requestIdList) {
                itemList.add(new NonSequentialPushNotificationWorkerItemVO(pushNotificationConfig, requestIdList))
            }
        }

        return itemList
    }
}
