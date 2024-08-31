package com.asaas.service.pushnotificationconfig

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.pushnotification.PushNotificationRequestEvent

import grails.transaction.Transactional

@Transactional
class PushNotificationConfigEventService {

    public void save(PushNotificationConfig config, List<PushNotificationRequestEvent> eventList) {
        List<PushNotificationConfigEvent> savedPushNotificationConfigEventList = PushNotificationConfigEvent.query([configId: config.id, includeDeleted: true]).list()

        restoreEventsIfNecessary(savedPushNotificationConfigEventList, eventList)
        deleteEventsIfNecessary(savedPushNotificationConfigEventList, eventList)

        List<PushNotificationRequestEvent> eventsToSave = eventList.findAll { !savedPushNotificationConfigEventList.event.contains(it) }
        if (!eventsToSave) return

        for (PushNotificationRequestEvent event : eventsToSave) {
            PushNotificationConfigEvent pushNotificationConfigEvent = new PushNotificationConfigEvent()
            pushNotificationConfigEvent.config = config
            pushNotificationConfigEvent.event = event

            pushNotificationConfigEvent.save(failOnError: true)
        }
    }

    public void delete(Long configId) {
        List<PushNotificationConfigEvent> events = PushNotificationConfigEvent.query([configId: configId]).list()

        for (PushNotificationConfigEvent event : events) {
            event.deleted = true

            event.save(failOnError: true)
        }
    }

    private void restoreEventsIfNecessary(List<PushNotificationConfigEvent> savedPushNotificationConfigEventList, List<PushNotificationRequestEvent> eventList) {
        List<PushNotificationConfigEvent> eventsToRestore = savedPushNotificationConfigEventList.findAll { it.deleted && eventList.contains(it.event) }
        if (!eventsToRestore) return

        for (PushNotificationConfigEvent event : eventsToRestore) {
            event.deleted = false

            event.save(failOnError: true)
        }
    }

    private void deleteEventsIfNecessary(List<PushNotificationConfigEvent> savedPushNotificationConfigEventList, List<PushNotificationConfigEvent> eventList) {
        List<PushNotificationConfigEvent> eventsToDelete = savedPushNotificationConfigEventList.findAll { !it.deleted && !eventList.contains(it.event) }
        if (!eventsToDelete) return

        for (PushNotificationConfigEvent event : eventsToDelete) {
            event.deleted = true

            event.save(failOnError: true)
        }
    }
}
