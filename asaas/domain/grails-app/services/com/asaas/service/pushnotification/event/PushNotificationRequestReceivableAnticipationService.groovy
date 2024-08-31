package com.asaas.service.pushnotification.event

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestReceivableAnticipation
import com.asaas.domain.pushnotification.PushNotificationType
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestReceivableAnticipationService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, ReceivableAnticipation receivableAnticipation) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(receivableAnticipation.customer.id, event, [:]).list()

        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)

            PushNotificationRequestReceivableAnticipation pushNotificationRequestReceivableAnticipation = new PushNotificationRequestReceivableAnticipation()
            pushNotificationRequestReceivableAnticipation.pushNotificationRequest = pushNotificationRequest
            pushNotificationRequestReceivableAnticipation.receivableAnticipation = receivableAnticipation
            pushNotificationRequestReceivableAnticipation.save(failOnError: true)
        }
    }
}
