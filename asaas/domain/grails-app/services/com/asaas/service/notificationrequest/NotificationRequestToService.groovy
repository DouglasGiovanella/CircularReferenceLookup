package com.asaas.service.notificationrequest

import com.asaas.domain.notification.NotificationRequest;
import com.asaas.domain.notification.NotificationRequestTo;

import grails.transaction.Transactional

@Transactional
class NotificationRequestToService {

	public NotificationRequestTo save(NotificationRequestTo notificationRequestTo) {
		notificationRequestTo.save(flush: true, failOnError: true)
		
		return notificationRequestTo
	}
}
