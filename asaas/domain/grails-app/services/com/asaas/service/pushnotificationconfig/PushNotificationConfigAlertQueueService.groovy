package com.asaas.service.pushnotificationconfig

import com.asaas.customer.CustomerStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigAlertQueue
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.exception.BusinessException
import com.asaas.pushnotification.PushNotificationConfigAlertType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class PushNotificationConfigAlertQueueService {

    public void save(PushNotificationConfig pushNotificationConfig, PushNotificationConfigAlertType type) {
        save(pushNotificationConfig, type, null)
    }

    public void save(PushNotificationConfig pushNotificationConfig, PushNotificationConfigAlertType type, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        CustomerStatus customerStatus = pushNotificationConfig.provider.status
        if (customerStatus.isInactive()) return

        PushNotificationConfigAlertQueue pushNotificationConfigAlertQueue = new PushNotificationConfigAlertQueue()
        pushNotificationConfigAlertQueue.pushNotificationConfig = pushNotificationConfig
        if (pushNotificationRequestAttempt) pushNotificationConfigAlertQueue.pushNotificationRequestAttempt = pushNotificationRequestAttempt
        pushNotificationConfigAlertQueue.type = type
        pushNotificationConfigAlertQueue.scheduleDate = getScheduleDate(type)

        pushNotificationConfigAlertQueue.save(failOnError: true)
    }

    public void onPoolResumed(PushNotificationConfig pushNotificationConfig) {
        List<PushNotificationConfigAlertQueue> pushNotificationConfigAlertQueue = PushNotificationConfigAlertQueue.query([pushNotificationConfig: pushNotificationConfig, sent: false]).list()

        for (PushNotificationConfigAlertQueue alertQueue : pushNotificationConfigAlertQueue) {
            alertQueue.deleted = true
            alertQueue.save(failOnError: true)
        }
    }

    public void deleteAllFromCustomer(Customer customer) {
        List<Long> pushNotificationConfigAlertQueue = PushNotificationConfigAlertQueue.allNotSentFromCustomer(customer).list()

        for (PushNotificationConfigAlertQueue alertQueue : pushNotificationConfigAlertQueue) {
            alertQueue.deleted = true
            alertQueue.save(failOnError: true)
        }
    }

    private Date getScheduleDate(PushNotificationConfigAlertType type) {
        if (type.isQueueInterruptedSevenDays() || type.isQueueInterruptedFourteenDays()) {
            Integer daysToSchedule = 7

            return CustomDateUtils.sumDays(new Date(), daysToSchedule).clearTime()
        }

        return new Date().clearTime()
    }
}
