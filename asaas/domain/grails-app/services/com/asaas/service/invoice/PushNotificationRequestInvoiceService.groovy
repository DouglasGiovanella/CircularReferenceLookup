package com.asaas.service.invoice

import com.asaas.domain.invoice.Invoice
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestInvoiceService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, Invoice invoice) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(invoice.customer.id, event, [:]).list()

        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)
            pushNotificationRequest.invoice = invoice

            pushNotificationRequest.save(failOnError: true)
        }
    }

}