package com.asaas.service.notification.dispatcher

import com.asaas.integration.notificationdispatcher.NotificationDispatcherManager

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherNotificationRequestHealthCheckManagerService {

    public Boolean emailQueueDelay() {
        return doRequest("emailQueueDelay")
    }

    public Boolean smsQueueDelay() {
        return doRequest("smsQueueDelay")
    }

    public Boolean whatsAppQueueDelay() {
        return doRequest("whatsAppQueueDelay")
    }

    private Boolean doRequest(String path) {
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.logged = false
        manager.returnAsList = false
        manager.get("/paymentNotificationRequestHealthCheck/$path", [:])
        return manager.isSuccessful()
    }
}
