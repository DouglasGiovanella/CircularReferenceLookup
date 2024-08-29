package com.asaas.service.pushnotification.event

import com.asaas.domain.bill.Bill
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestBill
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestBillService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, Bill bill) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(bill.customer.id, event, [:]).list()

        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)

            PushNotificationRequestBill pushNotificationRequestBill = new PushNotificationRequestBill()
            pushNotificationRequestBill.pushNotificationRequest = pushNotificationRequest
            pushNotificationRequestBill.bill = bill
            pushNotificationRequestBill.save(failOnError: true)
        }
    }
}
