package com.asaas.service.pushnotification.event

import com.asaas.api.ApiPaymentParser
import com.asaas.domain.payment.Payment
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigApplication
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestPaymentEvent
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationRequestPaymentEventService {

    def pushNotificationRequestService
    def pushNotificationRequestProcessService

    public void buildAndSave(Payment payment, PushNotificationRequestEvent event, Map additionalInfo = null) {
        save(payment, event, { Integer apiVersion, PushNotificationConfigApplication application ->
            Map paymentMap = ApiPaymentParser.buildResponseItem(payment, [
                apiVersion: apiVersion,
                expandCustomer: false,
                suppressMobileExtraData: true,
                buildDescription: application?.isPluga()
            ])

            Map pushMap = [payment: paymentMap]
            if (additionalInfo) {
                pushMap.additionalInfo = additionalInfo
            }

            return pushMap
        })
    }

    private void save(Payment payment, PushNotificationRequestEvent event, Closure buildData) {
        List<PushNotificationConfig> pushNotificationConfigList = PushNotificationConfigEvent.enabledConfig(payment.provider.id, event, [:]).list()

        for (PushNotificationConfig pushNotificationConfig : pushNotificationConfigList) {
            Map data = buildData(pushNotificationConfig.apiVersion, pushNotificationConfig.application)
            String payload = pushNotificationRequestProcessService.convertToJson(pushNotificationConfig.apiVersion, data)

            Boolean hasExactlyEqualsRequestInQueue = PushNotificationRequestPaymentEvent.query([pushNotificationRequestConfig: pushNotificationConfig, pushNotificationRequestSent: false, pushNotificationRequestEvent: event, payment: payment, data: payload, exists: true]).get().asBoolean()
            if (hasExactlyEqualsRequestInQueue) continue

            PushNotificationRequestPaymentEvent lastPushNotificationRequestPaymentEvent = PushNotificationRequestPaymentEvent.query([pushNotificationRequestConfig: pushNotificationConfig, pushNotificationRequestEvent: event, payment: payment]).get()
            if (lastPushNotificationRequestPaymentEvent?.data == payload) continue

            PushNotificationRequest pushNotificationRequest = pushNotificationRequestService.save(pushNotificationConfig, event)
            PushNotificationRequestPaymentEvent pushNotificationRequestPaymentEvent = new PushNotificationRequestPaymentEvent()
            pushNotificationRequestPaymentEvent.pushNotificationRequest = pushNotificationRequest
            pushNotificationRequestPaymentEvent.payment = payment
            pushNotificationRequestPaymentEvent.data = payload
            pushNotificationRequestPaymentEvent.save(failOnError: true)
        }
    }

}
