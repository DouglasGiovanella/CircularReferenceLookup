package com.asaas.service.notification

import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.notification.NotificationRequestToEvent
import com.asaas.email.AsaasEmailEvent
import grails.transaction.Transactional

@Transactional
class NotificationRequestToEventService {

    public NotificationRequestToEvent saveOrUpdate(NotificationRequestTo notificationRequestTo, AsaasEmailEvent event, Date eventDate) {
        NotificationRequestToEvent notificationRequestToEvent = NotificationRequestToEvent.query([notificationRequestToId: notificationRequestTo.id, event: event]).get()

        if (!notificationRequestToEvent) {
            notificationRequestToEvent = new NotificationRequestToEvent()
            notificationRequestToEvent.notificationRequestTo = notificationRequestTo
            notificationRequestToEvent.event = event
        }

        notificationRequestToEvent.eventDate = eventDate

        return notificationRequestToEvent.save(failOnError: true, flush: true)
    }
}
