package com.asaas.service.pushnotification.event

import com.asaas.api.ApiMobilePhoneRechargeParser
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestMobilePhoneRechargeEvent
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

import groovy.json.JsonOutput

@Transactional
class PushNotificationRequestMobilePhoneRechargeService {

    def pushNotificationRequestService

    public void save(PushNotificationRequestEvent event, MobilePhoneRecharge mobilePhoneRecharge) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(mobilePhoneRecharge.customer.id, event, [:]).list()

        if (!pushNotificationConfigList) return

        Map data = [mobilePhoneRecharge: ApiMobilePhoneRechargeParser.buildResponseItem(mobilePhoneRecharge)]

        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)

            PushNotificationRequestMobilePhoneRechargeEvent pushNotificationRequestMobilePhoneRechargeEvent = new PushNotificationRequestMobilePhoneRechargeEvent()
            pushNotificationRequestMobilePhoneRechargeEvent.pushNotificationRequest = pushNotificationRequest
            pushNotificationRequestMobilePhoneRechargeEvent.data = JsonOutput.toJson(data)
            pushNotificationRequestMobilePhoneRechargeEvent.save(failOnError: true)
        }
    }

}
