package com.asaas.service.message

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class PushNotificationMessageService {

    def grailsApplication
    def messageService

    public void sendPushNotificationRequestAttemptFail(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        try {
            String emailSubject = "Erro ao sincronizar eventos de Webhooks com a sua aplicação"
            String emailBody = messageService.buildTemplate("/mailTemplate/webhook/webhookFailWarning", [
                customerEmail: pushNotificationConfig.provider.email,
                webhookName: pushNotificationConfig.name,
                details: pushNotificationRequestAttempt
            ])
            messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, pushNotificationConfig.email, pushNotificationConfig.provider.providerName, emailSubject, emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("PushNotificationMessageService.sendPushNotificationRequestAttemptFail - Erro ao enviar email Push Notification Config ID: [${pushNotificationConfig.id}]", exception)
        }
    }

    public void sendPushNotificationRequestInterrupted(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        try {
            String emailSubject = "⚠️ A sincronização de eventos de Webhooks foi interrompida"
            String emailBody = messageService.buildTemplate("/mailTemplate/webhook/webhookInterruptedWarning", [
                customerEmail: pushNotificationConfig.provider.email,
                webhookName: pushNotificationConfig.name,
                details: pushNotificationRequestAttempt
            ])

            messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, pushNotificationConfig.email, pushNotificationConfig.provider.providerName, emailSubject, emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("PushNotificationMessageService.sendPushNotificationRequestInterrupted - Erro ao enviar email Push Notification Config ID: [${pushNotificationConfig.id}]", exception)
        }
    }

    public void sendPushNotificationSevenDaysInterrupted(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        try {
            String emailSubject = "A sincronização de Webhooks está pausada há 7 dias"
            String emailBody = messageService.buildTemplate("/mailTemplate/webhook/webhookInterruptedSevenDays", [
                customerEmail: pushNotificationConfig.provider.email,
                webhookName: pushNotificationConfig.name,
                details: pushNotificationRequestAttempt
            ])

            messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, pushNotificationConfig.email, pushNotificationConfig.provider.providerName, emailSubject, emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("PushNotificationMessageService.sendPushNotificationSevenDaysInterrupted - Erro ao enviar email Push Notification Config ID: [${pushNotificationConfig.id}]", exception)
        }
    }

    public void sendPushNotificationFourteenDaysInterrupted(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        try {
            String emailSubject = "Uma sincronização de Webhooks está 14 dias pausada"
            String emailBody = messageService.buildTemplate("/mailTemplate/webhook/webhookInterruptedFourteenDays", [
                customerEmail: pushNotificationConfig.provider.email,
                webhookName: pushNotificationConfig.name,
                details: pushNotificationRequestAttempt
            ])

            messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, pushNotificationConfig.email, pushNotificationConfig.provider.providerName, emailSubject, emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("PushNotificationMessageService.sendPushNotificationFourteenDaysInterrupted - Erro ao enviar email Push Notification Config ID: [${pushNotificationConfig.id}]", exception)
        }
    }
}
