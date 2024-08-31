package com.asaas.service.notificationtemplate

import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.CustomNotificationTemplate
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.dto.NotificationDispatcherExternalTemplateAdapter
import com.asaas.notification.dispatcher.dto.NotificationDispatcherUpsertCustomTemplateRequestDTO
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationMessageType
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.notification.NotificationType
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import java.util.regex.Matcher

@Transactional
class NotificationTemplateService {

    def customerNotificationConfigService
    def externalNotificationTemplateService
    def featureFlagService
    def notificationDispatcherManagerService
    def sendGridSubUserService

    public List<NotificationTemplate> generateStaticListWithAllEvents(Customer provider, NotificationType templateType, NotificationReceiver receiver) {
        List<NotificationTemplate> notificationTemplates = []

        NotificationMessageType messageType = provider.customerConfig.notificationMessageType

        for (NotificationEvent templateEvent : NotificationEvent.listToCreate()) {
            List<NotificationSchedule> scheduleList = NotificationTrigger.query([distinct: "schedule", event: templateEvent]).list()

            for (NotificationSchedule schedule : scheduleList) {
                NotificationTemplate template = new NotificationTemplate()

                template.provider = provider
                template.event = templateEvent
                template.messageType = messageType
                template.receiver = receiver
                template.type = templateType
                template.schedule = schedule
                template.name = buildName(template)

                notificationTemplates.add(template)
            }
        }

        return notificationTemplates
    }

    public NotificationTemplate save(NotificationTemplate notificationTemplate, params) {
        notificationTemplate.provider = Customer.get(params.providerId)
        notificationTemplate.event = params.event
        notificationTemplate.messageType = params.messageType
        notificationTemplate.receiver = params.receiver
        notificationTemplate.type = params.type
        notificationTemplate.schedule = params.schedule
        notificationTemplate.name = params.name
        notificationTemplate.language = params.language

        validateTextToAvoidDangerInjection(params.subject)
        notificationTemplate.subject = params.subject

        validateTextToAvoidDangerInjection(params.body)
        notificationTemplate.body = params.body

        validateTextToAvoidDangerInjection(params.message)
        notificationTemplate.message = params.message

        notificationTemplate.save(failOnError: true)

        return notificationTemplate
    }

    public NotificationTemplate saveFromCustomTemplate(CustomNotificationTemplate customTemplate) {
        final Boolean shouldManageCustomTemplateExternally = false

        Customer customer = customTemplate.customer

        customerNotificationConfigService.createCustomerNotificationConfigIfNotExists(customer)

        NotificationTemplate notificationTemplate = NotificationTemplate.query([
            providerId: customer.id,
            event: customTemplate.templateGroup.event,
            schedule: customTemplate.templateGroup.schedule,
            type: customTemplate.type,
            messageType: NotificationMessageType.PAYMENT,
            receiver: NotificationReceiver.CUSTOMER,
            isCustomTemplate: true
        ]).get()

        if (!notificationTemplate) notificationTemplate = buildFromCustomTemplate(customTemplate)

        if (notificationTemplate.type.isSms()) {
            notificationTemplate.message = customTemplate.body
        } else if (notificationTemplate.type.isEmail()) {
            notificationTemplate.preHeader = customTemplate.preHeader
            notificationTemplate.headerTitle = customTemplate.headerTitle

            if (!shouldManageCustomTemplateExternally) {
                sendGridSubUserService.createSendGridSubUserIfNecessary(customer)
                notificationTemplate.externalTemplate = externalNotificationTemplateService.saveFromNotificationTemplate(notificationTemplate, customTemplate)
            }
        }

        if (shouldManageCustomTemplateExternally) {
            synchronizeWithNotificationDispatcher(notificationTemplate, customTemplate)
        }

        return notificationTemplate.save(failOnError: true)
    }

    public void synchronizeWithNotificationDispatcher(NotificationTemplate notificationTemplate, CustomNotificationTemplate customTemplate) {
        NotificationDispatcherUpsertCustomTemplateRequestDTO requestDTO = new NotificationDispatcherUpsertCustomTemplateRequestDTO(customTemplate)

        NotificationDispatcherExternalTemplateAdapter externalTemplateAdapter = notificationDispatcherManagerService.upsertCustomTemplate(requestDTO)
        if (!notificationTemplate.type.isEmail()) return

        notificationTemplate.externalTemplate = externalNotificationTemplateService.upsertFromNotificationDispatcher(customTemplate, externalTemplateAdapter)
    }

    public void validateTextToAvoidDangerInjection(String text) {
        Matcher injectedCodeFinder = text =~ /(\$\{[^\$]*\})|(\$\S*)/

        while (injectedCodeFinder.find()) {
            String injectedCode = StringUtils.removeWhitespaces(injectedCodeFinder.group()).toLowerCase()

            for (String blackListWord : NotificationTemplate.injectionBlacklist) {
                if (!injectedCode.find(blackListWord)) continue

                AsaasLogger.warn("NotificationTemplateService.validateTextToAvoidDangerInjection >> Detectamos uma tentativa de inserir código perigoso no template de notificação: O match com a blacklist foi: ${blackListWord}.O texto inserido foi:[${injectedCode}]")
                throw new Exception("Ocorreu um erro.")
            }
        }
    }

    public void copyFromDefaultTemplate(NotificationTemplate notificationTemplate) {
        NotificationTemplate defaultTemplate = NotificationTemplate.query(['provider[isNull]': true, event: notificationTemplate.event, messageType: notificationTemplate.messageType, receiver: notificationTemplate.receiver, type: notificationTemplate.type, schedule: notificationTemplate.schedule]).get()
        if (!defaultTemplate) return

        notificationTemplate.body = defaultTemplate.body
        notificationTemplate.subject = defaultTemplate.subject
        notificationTemplate.message = defaultTemplate.message
        notificationTemplate.language = defaultTemplate.language
        notificationTemplate.schedule = defaultTemplate.schedule
    }

    public NotificationTemplate create(params) {
        NotificationTemplate notificationTemplate = new NotificationTemplate()
        notificationTemplate.provider = Customer.get(params.providerId)
        notificationTemplate.event = params.event
        notificationTemplate.messageType = params.messageType
        notificationTemplate.receiver = params.receiver
        notificationTemplate.type = params.type
        notificationTemplate.name = params.name
        notificationTemplate.schedule = params.schedule

        return notificationTemplate
    }

    private NotificationTemplate buildFromCustomTemplate(CustomNotificationTemplate customTemplate) {
        NotificationTemplate notificationTemplate = new NotificationTemplate()
        notificationTemplate.provider = customTemplate.customer
        notificationTemplate.event = customTemplate.templateGroup.event
        notificationTemplate.schedule = customTemplate.templateGroup.schedule
        notificationTemplate.type = customTemplate.type
        notificationTemplate.messageType = NotificationMessageType.PAYMENT
        notificationTemplate.receiver = NotificationReceiver.CUSTOMER
        notificationTemplate.name = buildName(notificationTemplate)
        notificationTemplate.language = "pt_BR"
        notificationTemplate.isCustomTemplate = true

        return notificationTemplate
    }

    private String buildName(NotificationTemplate notificationTemplate) {
        String name = Utils.getMessageProperty("notificationEvent.title.${notificationTemplate.event}.${notificationTemplate.schedule}")
        name += " para "
        name += Utils.getMessageProperty("notificationReceiver.title.${notificationTemplate.receiver}")
        name += " (${notificationTemplate.type})"

        return name
    }
}
