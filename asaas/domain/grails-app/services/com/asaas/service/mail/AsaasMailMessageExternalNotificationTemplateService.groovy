package com.asaas.service.mail

import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.email.AsaasMailMessageExternalNotificationTemplate
import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.email.asaasmailmessage.AsaasMailMessageVO
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class AsaasMailMessageExternalNotificationTemplateService {

    def groovyPageRenderer
    def notificationRequestParameterService

    public AsaasMailMessageExternalNotificationTemplate save(AsaasMailMessage asaasMailMessage, AsaasMailMessageVO asaasMailMessageVO) {
        AsaasMailMessageExternalNotificationTemplate asaasMailMessageExternalNotificationTemplate = new AsaasMailMessageExternalNotificationTemplate()

        asaasMailMessageExternalNotificationTemplate.asaasMailMessage = asaasMailMessage
        asaasMailMessageExternalNotificationTemplate.externalTemplate = asaasMailMessageVO.externalTemplate
        asaasMailMessageExternalNotificationTemplate.externalTemplateData = getExternalTemplateData(asaasMailMessageVO)

        return asaasMailMessageExternalNotificationTemplate.save(failOnError: true)
    }

    private String getExternalTemplateData(AsaasMailMessageVO asaasMailMessageVO) {
        NotificationTemplate notificationTemplate = asaasMailMessageVO.notificationTemplate
        NotificationRequest notificationRequest = asaasMailMessageVO.notificationRequest

        Map defaultTemplateData = notificationRequestParameterService
            .buildDefaultMapForExternalTemplateNotification(notificationRequest)

        Map customTemplateData = notificationRequestParameterService
            .buildNotificationTemplatePropertiesMap(notificationRequest)

        customTemplateData.preHeader = CustomNotificationTemplate.removeRenderPrevention(notificationTemplate.preHeader)
        customTemplateData.headerTitle = CustomNotificationTemplate.removeRenderPrevention(notificationTemplate.headerTitle)

        customTemplateData.customerCustomNotificationTemplateHeader = groovyPageRenderer.render(
            template: "/notificationTemplates/customerCustomNotificationTemplateHeader",
            model: defaultTemplateData
        ).decodeHTML()

        customTemplateData.customerCustomNotificationTemplateFooter = groovyPageRenderer.render(
            template: "/notificationTemplates/customerCustomNotificationTemplateFooter",
            model: defaultTemplateData
        ).decodeHTML()

        return JsonOutput.toJson(customTemplateData)
    }
}
