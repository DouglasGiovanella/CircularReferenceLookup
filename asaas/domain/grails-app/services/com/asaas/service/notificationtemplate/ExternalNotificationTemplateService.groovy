package com.asaas.service.notificationtemplate

import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.ExternalNotificationTemplate
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.externalnotificationtemplate.ExternalNotificationTemplateRepository
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationProvider
import com.asaas.notification.dispatcher.dto.NotificationDispatcherExternalTemplateAdapter

import grails.transaction.Transactional

@Transactional
class ExternalNotificationTemplateService {

    def groovyPageRenderer
    def sendGridEmailNotificationTemplateService
    def notificationTemplatePropertyConditionService

    public ExternalNotificationTemplate save(NotificationTemplate template, CustomNotificationTemplate customTemplate) {
        if (template.externalTemplate) throw new RuntimeException("ExternalNotificationTemplateService.save -> O NotificationTemplate [${template.id}] já possui um ExternalNotificationTemplate.")
        if (!template.type.isEmail()) throw new RuntimeException("ExternalNotificationTemplateService.save -> O NotificationTemplate [${template.id}] não possui um type adequado para persistir como ExternalNotificationTemplate.")

        ExternalNotificationTemplate externalTemplate = new ExternalNotificationTemplate()

        externalTemplate.provider = NotificationProvider.SENDGRID
        externalTemplate.customTemplate = customTemplate
        externalTemplate.externalId = sendGridEmailNotificationTemplateService.createTemplate(template)

        externalTemplate.save(failOnError: true)

        return externalTemplate
    }

    public void saveVersion(NotificationTemplate template, ExternalNotificationTemplate externalTemplate) {
        if (!template.type.isEmail()) throw new RuntimeException("ExternalNotificationTemplateService.saveVersion -> O NotificationTemplate [${template.id}] não possui um type adequado para persistir como ExternalNotificationTemplate.")

        CustomNotificationTemplate customTemplate = externalTemplate.customTemplate

        String subject = prepareTemplateField(customTemplate.templateGroupId, customTemplate.subject)
        String body = buildExternalEmailTemplate(prepareTemplateField(customTemplate.templateGroupId, customTemplate.body))

        externalTemplate.externalVersionId = sendGridEmailNotificationTemplateService.createTemplateVersion(template, externalTemplate, subject, body)

        externalTemplate.save(failOnError: true)
    }

    public ExternalNotificationTemplate updateVersion(NotificationTemplate template, CustomNotificationTemplate customTemplate) {
        ExternalNotificationTemplate externalTemplate = template.externalTemplate

        if (!externalTemplate) throw new RuntimeException("ExternalNotificationTemplateService.updateVersion -> Não existe um ExternalNotificationTemplate para o NotificationTemplate [${template.id}].")
        if (!template.type.isEmail()) throw new RuntimeException("ExternalNotificationTemplateService.updateVersion -> O NotificationTemplate [${template.id}] não possui um type adequado para persistir como ExternalNotificationTemplate.")

        externalTemplate.customTemplate = customTemplate

        String subject = prepareTemplateField(customTemplate.templateGroupId, customTemplate.subject)
        String body = buildExternalEmailTemplate(prepareTemplateField(customTemplate.templateGroupId, customTemplate.body))

        sendGridEmailNotificationTemplateService.updateTemplateVersion(template, subject, body)

        externalTemplate.save(failOnError: true)

        return externalTemplate
    }

    public ExternalNotificationTemplate saveFromNotificationTemplate(NotificationTemplate template, CustomNotificationTemplate customTemplate) {
        ExternalNotificationTemplate externalTemplate

        try {
            if (template.externalTemplate) {
                externalTemplate = updateVersion(template, customTemplate)
                return externalTemplate
            }

            externalTemplate = save(template, customTemplate)
            saveVersion(template, externalTemplate)

            return externalTemplate
        } catch (Exception exception) {
            String errorMessage = "NotificationTemplateService.saveFromNotificationTemplate >> Erro ao requisitar o parceiro no fluxo de aprovação de templates para o CustomNotificationTemplate [${ customTemplate.id }]"
            if (externalTemplate?.externalId) errorMessage += " Template [${ externalTemplate.externalId }]"

            AsaasLogger.error(errorMessage, exception)
            throw new RuntimeException(exception.message)
        }
    }

    public ExternalNotificationTemplate upsertFromNotificationDispatcher(CustomNotificationTemplate customTemplate, NotificationDispatcherExternalTemplateAdapter externalTemplateAdapter) {
        ExternalNotificationTemplate externalTemplate = ExternalNotificationTemplateRepository.query([externalId: externalTemplateAdapter.externalId]).get()

        if (!externalTemplate) {
            externalTemplate = new ExternalNotificationTemplate()
            externalTemplate.customTemplate = customTemplate
            externalTemplate.externalId = externalTemplateAdapter.externalId
            externalTemplate.provider = NotificationProvider.SENDGRID
        }

        externalTemplate.externalVersionId = externalTemplateAdapter.externalVersionId

        return externalTemplate.save(failOnError: true)
    }

    private String buildExternalEmailTemplate(String body) {
        StringBuilder stringBuilder = new StringBuilder()

        stringBuilder.append(groovyPageRenderer.render(template: "/notificationTemplates/customerNotificationHeader", model: [isExternalTemplate: true]))
        stringBuilder.append("<div style=\"max-width: 792px; padding: 0 40px; color: #212529; font-size: 14px; line-height: 20px; margin: 0 auto; text-align: left;\">")
        stringBuilder.append(body)
        stringBuilder.append("</div>")
        stringBuilder.append("{{{customerCustomNotificationTemplateFooter}}}")

        return stringBuilder.toString()
    }

    private String prepareTemplateField(Long templateGroupId, String field) {
        String unsafeField = CustomNotificationTemplate.removeRenderPrevention(field)
        return notificationTemplatePropertyConditionService.translateEmailTemplateProperties(templateGroupId, unsafeField)
    }
}
