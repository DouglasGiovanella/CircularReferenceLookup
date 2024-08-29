package com.asaas.service.notification

import com.asaas.domain.notification.NotificationDispatcherPaymentNotificationOutbox
import com.asaas.domain.notification.NotificationDispatcherCustomerOutbox
import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class NotificationDispatcherOutboxHealthCheckService {

    public Boolean checkCustomerDelay() {
        final Integer toleranceSeconds = 30
        Date oldestEventDate = NotificationDispatcherCustomerOutbox.query([column: "dateCreated", order: "asc"]).get() as Date

        return !isQueueIsDelayed(oldestEventDate, toleranceSeconds)
    }

    public Boolean checkCustomerAccountDelay() {
        final Integer toleranceSeconds = 30
        Date oldestEventDate = NotificationDispatcherPaymentNotificationOutbox.query([column: "dateCreated", order: "asc"]).get() as Date

        return !isQueueIsDelayed(oldestEventDate, toleranceSeconds)
    }

    private Boolean isQueueIsDelayed(Date oldestDate, Integer toleranceSeconds) {
        if (!oldestDate) return false

        Long oldestEventInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - oldestDate.getTime())
        Boolean delayDetected = oldestEventInSeconds > toleranceSeconds

        return delayDetected
    }
}
