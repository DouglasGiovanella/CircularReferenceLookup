package com.asaas.service.integration.sendgrid

import com.asaas.domain.notification.ExternalNotificationTemplate
import com.asaas.domain.notification.NotificationTemplate

import grails.transaction.Transactional

@Transactional
class SendGridEmailNotificationTemplateService {

    def sendGridManagerService

    public String createTemplate(NotificationTemplate template) {
        return sendGridManagerService.createTemplate(template.provider, buildName(template))
    }

    public String createTemplateVersion(NotificationTemplate template, ExternalNotificationTemplate externalTemplate, String subject, String body) {
        Map requestBody = buildRequestBody(buildName(template), subject, body)
        String externalVersionId = sendGridManagerService.createTemplateVersion(template.provider, externalTemplate.externalId, requestBody)

        return externalVersionId
    }

    public void updateTemplateVersion(NotificationTemplate template, String subject, String body) {
        ExternalNotificationTemplate externalTemplate = template.externalTemplate
        Map requestBody = buildRequestBody(buildName(template), subject, body)

        sendGridManagerService.updateTemplateVersion(template.provider, externalTemplate.externalId, externalTemplate.externalVersionId, requestBody)
    }

    private Map buildRequestBody(String name, String subject, String body) {
        Map requestBody = [:]
        requestBody."name" = name
        requestBody."subject" = subject
        requestBody."body" = body

        return requestBody
    }

    private String buildName(NotificationTemplate template) {
        return "${template.type.name()}_${template.event.name()}_${template.schedule.name()}"
    }
}
