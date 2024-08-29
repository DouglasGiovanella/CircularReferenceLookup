package com.asaas.service.notificationrequesttemplate

import com.asaas.domain.notification.NotificationRequestTemplate
import com.asaas.notificationrequest.vo.NotificationRequestTemplateVO

import grails.transaction.Transactional

@Transactional
class NotificationRequestTemplateService {

    public saveIfNecessary(NotificationRequestTemplateVO notificationRequestTemplateVO) {
        if (!notificationRequestTemplateVO.notificationTemplate?.id) return

        NotificationRequestTemplate notificationRequestTemplate = new NotificationRequestTemplate()
        notificationRequestTemplate.notificationRequest = notificationRequestTemplateVO.notificationRequest
        notificationRequestTemplate.notificationTemplate = notificationRequestTemplateVO.notificationTemplate
        notificationRequestTemplate.save(failOnError: true)
    }
}
