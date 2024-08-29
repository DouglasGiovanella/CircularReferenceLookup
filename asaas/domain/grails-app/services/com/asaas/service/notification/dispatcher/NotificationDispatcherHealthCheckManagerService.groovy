package com.asaas.service.notification.dispatcher

import com.asaas.integration.notificationdispatcher.NotificationDispatcherManager

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherHealthCheckManagerService {

    public Boolean healthCheck() {
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.logged = false
        manager.get("/healthCheck", [:])

        return manager.isSuccessful()
    }
}
