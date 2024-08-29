package com.asaas.service.customer

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestAccountStatusService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, Long customerId) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(customerId, event, [:]).list()
        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            pushNotificationRequestService.save(pushNotificationConfig, event)
        }
    }
}