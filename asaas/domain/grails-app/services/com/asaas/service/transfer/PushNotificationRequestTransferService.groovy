package com.asaas.service.transfer

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.transfer.Transfer
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestTransferService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, Transfer transfer) {
        if (transfer.pixTransaction?.type?.isCreditRefund()) return

        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(transfer.customer.id, event, [:]).list()
        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)
            pushNotificationRequest.transferId = transfer.id

            pushNotificationRequest.save(failOnError: true)
        }
    }

}