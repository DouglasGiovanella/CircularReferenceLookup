package com.asaas.service.notification

import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.notification.NotificationEvent
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class NotificationUpdateService {

    def notificationService
    def notificationDispatcherPaymentNotificationOutboxService

    public Notification update(Notification notification, Map params) {

        Map parsedFields = parseParams(notification, params)

        Notification validatedNotification = validate(notification, parsedFields)
        if (validatedNotification.hasErrors()) return validatedNotification

        if (parsedFields.containsKey("emailEnabledForProvider")) {
            notification.emailEnabledForProvider = parsedFields.emailEnabledForProvider
        }

        if (parsedFields.containsKey("smsEnabledForProvider")) {
            notification.smsEnabledForProvider = parsedFields.smsEnabledForProvider
        }

        if (parsedFields.containsKey("emailEnabledForCustomer")) {
            notification.emailEnabledForCustomer = parsedFields.emailEnabledForCustomer
        }

        if (parsedFields.containsKey("smsEnabledForCustomer")) {
            notification.smsEnabledForCustomer = parsedFields.smsEnabledForCustomer
        }

        if (parsedFields.containsKey("phoneCallEnabledForCustomer")) {
            notification.phoneCallEnabledForCustomer = parsedFields.phoneCallEnabledForCustomer
        }

        if (parsedFields.containsKey("whatsappEnabledForCustomer")) {
            notification.whatsappEnabledForCustomer = parsedFields.whatsappEnabledForCustomer
        }

        if (parsedFields.containsKey("scheduleOffset")) {
            notification.trigger = notificationService.findNotificationTrigger(notification.trigger.event, notification.trigger.schedule, parsedFields.scheduleOffset)
        }

        if (parsedFields.containsKey("enabled")) {
            notification.enabled = parsedFields.enabled
        }

        if (UserUtils.actorIsApiKey() || UserUtils.actorIsSystem()) {
            AuditLogListener.withoutAuditLog({ notification.save(flush: true, failOnError: true) })
        } else {
            notification.save(failOnError: true)
        }

        notificationDispatcherPaymentNotificationOutboxService.saveNotificationUpdated(notification)

        return notification
    }

    private Notification validate(Notification notification, Map params) {
        Notification validatedNotification = new Notification()

        Customer provider = notification.customerAccount.provider

        if (params.containsKey("emailEnabledForProvider")) {
            if (params.emailEnabledForProvider && !provider.email) {
                return DomainUtils.addError(validatedNotification, "Seu cadastro não possui um email para habilitar esta notificação.")
            }
        }

        if (params.containsKey("smsEnabledForProvider")) {
            if (params.smsEnabledForProvider && !provider.mobilePhone) {
                return DomainUtils.addError(validatedNotification, "Seu cadastro não possui um número de celular para habilitar esta notificação.")
            }
        }

        if (params.containsKey("emailEnabledForCustomer")) {
            if (notification.updateBlocked) {
                return DomainUtils.addError(validatedNotification, "Esta notificação é obrigatória e não pode ser alterada")
            }

            Boolean emailEnabledForCustomer = Utils.toBoolean(params.emailEnabledForCustomer)
            if (emailEnabledForCustomer && !notification.customerAccount.email) {
                return DomainUtils.addError(validatedNotification, "Cliente não possui email para contato.")
            }
        }

        if (params.containsKey("smsEnabledForCustomer")) {
            if (notification.updateBlocked) {
                return DomainUtils.addError(validatedNotification, "Esta notificação é obrigatória e não pode ser alterada")
            }

            if (params.smsEnabledForCustomer && !notification.customerAccount.mobilePhone) {
                return DomainUtils.addError(validatedNotification, "Cliente não possui telefone informado para envio de notificação.")
            }
        }

        if (params.containsKey("phoneCallEnabledForCustomer")) {
            if (notification.updateBlocked) {
                return DomainUtils.addError(validatedNotification, "Esta notificação é obrigatória e não pode ser alterada")
            }

            if (params.phoneCallEnabledForCustomer) {
                if (!notification.customerAccount.mobilePhone && !notification.customerAccount.phone) {
                    return DomainUtils.addError(validatedNotification, "Cliente não possui telefone informado para envio de notificação.")
                }

                if (!NotificationEvent.listToNotifyByPhoneCall().contains(notification.trigger.event)) {
                    return DomainUtils.addError(validatedNotification, "Evento inválido para ativação da notificação por voz.")
                }
            }
        }

        if (params.containsKey("whatsappEnabledForCustomer")) {
            if (notification.updateBlocked) {
                return DomainUtils.addError(validatedNotification, "Esta notificação é obrigatória e não pode ser alterada")
            }

            if (params.whatsappEnabledForCustomer) {
                if (!notification.customerAccount.mobilePhone) {
                    return DomainUtils.addError(validatedNotification, "Cliente não possui telefone celular informado para envio de notificação.")
                }

                if (!NotificationEvent.listToNotifyByWhatsApp().contains(notification.trigger.event)) {
                    return DomainUtils.addError(validatedNotification, "Evento inválido para ativação da notificação por WhatsApp.")
                }
            }
        }

        if (params.containsKey("scheduleOffset")) {
            if (!params.scheduleOffset) {
                return DomainUtils.addError(validatedNotification, "O número de dias informado é inválido")
            }

            if (notification.trigger.event == NotificationEvent.PAYMENT_DUEDATE_WARNING) {
                if (params.scheduleOffset != 0 && notification.trigger.scheduleOffset == 0) {
                    return DomainUtils.addError(validatedNotification, "O número de dias informado é inválido")
                }

                if (notification.trigger.scheduleOffset > 0 && params.scheduleOffset == 0) {
                    return DomainUtils.addError(validatedNotification, "O número de dias informado é inválido")
                }
            }

            NotificationTrigger notificationTrigger = notificationService.findNotificationTrigger(notification.trigger.event, notification.trigger.schedule, params.scheduleOffset)
            if (!notificationTrigger) {
                return DomainUtils.addError(validatedNotification, "O número de dias informado é inválido")
            }
        }

        if (params.containsKey("enabled")) {
            if (notification.updateBlocked) {
                return DomainUtils.addError(validatedNotification, "Esta notificação é obrigatória e não pode ser alterada")
            }
        }

        return validatedNotification
    }

    private Map parseParams(Notification notification, Map params) {
        Map parsedFields = [:]

        if (params.containsKey("emailEnabledForProvider") && Utils.isValidBooleanValue(params.emailEnabledForProvider)) {
            parsedFields.emailEnabledForProvider = Utils.toBoolean(params.emailEnabledForProvider)
        }

        if (params.containsKey("smsEnabledForProvider") && Utils.isValidBooleanValue(params.smsEnabledForProvider)) {
            parsedFields.smsEnabledForProvider = Utils.toBoolean(params.smsEnabledForProvider)
        }

        if (params.containsKey("emailEnabledForCustomer") && Utils.isValidBooleanValue(params.emailEnabledForCustomer)) {
            parsedFields.emailEnabledForCustomer = Utils.toBoolean(params.emailEnabledForCustomer)
        }

        if (params.containsKey("smsEnabledForCustomer") && Utils.isValidBooleanValue(params.smsEnabledForCustomer)) {
            parsedFields.smsEnabledForCustomer = Utils.toBoolean(params.smsEnabledForCustomer)
        }

        if (params.containsKey("phoneCallEnabledForCustomer") && Utils.isValidBooleanValue(params.phoneCallEnabledForCustomer)) {
            parsedFields.phoneCallEnabledForCustomer = Utils.toBoolean(params.phoneCallEnabledForCustomer)
        }

        if (params.containsKey("whatsappEnabledForCustomer") && Utils.isValidBooleanValue(params.whatsappEnabledForCustomer)) {
            parsedFields.whatsappEnabledForCustomer = Utils.toBoolean(params.whatsappEnabledForCustomer)
        }

        if (params.containsKey("scheduleOffset") && params.scheduleOffset != notification.trigger.scheduleOffset) {
            parsedFields.scheduleOffset = Utils.toInteger(params.scheduleOffset)
        }

        if (params.containsKey("enabled") && Utils.isValidBooleanValue(params.enabled)) {
            parsedFields.enabled = Utils.toBoolean(params.enabled)
        }

        return parsedFields
    }
}
