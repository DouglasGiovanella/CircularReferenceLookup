package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.notification.Notification
import com.asaas.exception.BusinessException
import com.asaas.exception.NotificationNotFoundException
import com.asaas.notification.NotificationEvent
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiNotificationService extends ApiBaseService {

	def apiResponseBuilderService
	def notificationService
	def notificationUpdateService

	def find(params) {
		try {
			return build(notificationService.findNotification(params.id, getProvider(params)))
		} catch(NotificationNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

	def list(params) {
		setCustomerAccountIntoParams(params)
		def notificationList = notificationService.list(getCustomer(params), getProvider(params), getLimit(params), getOffset(params), params)

		def notifications = []
		for(n in notificationList) {
			def notification = build(n)
			notifications << notification
		}

		return apiResponseBuilderService.buildList(notifications, getLimit(params), getOffset(params), notificationList.totalCount)
	}

    def save(params) {
        Map fields = parseFields(params)

        if (!fields.event) return apiResponseBuilderService.buildErrorFrom("trigger_not_informed", Utils.getMessageProperty("notification.trigger.invalid.event"))
        if (!fields.customer) return apiResponseBuilderService.buildErrorFrom("customer_not_informed", Utils.getMessageProperty("invalid.com.asaas.domain.notification.Notification.customerAccount"))

        CustomerAccount customerAccount = CustomerAccount.find(fields.customer, getProvider(params))

        Map queryMap = [customerAccount: customerAccount, event: fields.event]

        if (fields.event == NotificationEvent.CUSTOMER_PAYMENT_OVERDUE || fields.event == NotificationEvent.PAYMENT_DUEDATE_WARNING) {
            if (fields.scheduleOffset) {
                queryMap."scheduleOffset[ne]" = 0
            } else {
                queryMap.scheduleOffset = 0
            }
        }

        Notification notification = Notification.query(queryMap).get()

        Notification updatedNotification = notificationUpdateService.update(notification, fields)
        if (updatedNotification.hasErrors()) return apiResponseBuilderService.buildErrorList(updatedNotification)

        return apiResponseBuilderService.buildSuccess(build(updatedNotification))
    }

    def update(params) {
        try {
            Map fields = parseFields(params)

            Notification notification = notificationService.findNotification(fields.id, getProvider(params))

            Notification updatedNotification = notificationUpdateService.update(notification, fields)
            if (updatedNotification.hasErrors()) return apiResponseBuilderService.buildErrorList(updatedNotification)

            return apiResponseBuilderService.buildSuccess(build(updatedNotification))
        } catch (NotificationNotFoundException e) {
            return apiResponseBuilderService.buildError("invalid", "notification", "id", ["Notificação"])
        }
    }

    public Map batchUpdate(Map params) {
        if (!ApiBaseParser.containsKeyAndInstanceOf(params, "notifications", List)) {
            throw new BusinessException("Informe a lista de notificações a serem atualizadas.")
        }

        if (params.notifications.any { !(it instanceof Map) }) {
            throw new BusinessException("O formato dos itens da lista de notificações enviada é inválido.")
        }

        Long providerId = getProvider(params)
        Long customerAccountId = CustomerAccount.validateOwnerAndRetrieveId(params.customer, providerId)

        List<Map> updatedListNotification = []

        for (Map notificationItem : params.notifications) {
            Map fields = parseFields(notificationItem)
            if (!fields.id) throw new BusinessException("Deve ser informado o Id de todas as notificações pertencentes ao seu cliente.")

            Notification notification = notificationService.findNotification(fields.id, providerId, customerAccountId)

            Notification updatedNotification = notificationUpdateService.update(notification, fields)
            if (updatedNotification.hasErrors()) {
                throw new BusinessException("Falha na atualização da notificação ${fields.id}: ${DomainUtils.getFirstValidationMessage(updatedNotification)}")
            }

            updatedListNotification.add(build(updatedNotification))
        }

        return apiResponseBuilderService.buildSuccess([notifications: updatedListNotification])
    }

	def delete(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("invalid", "notification", "", ["Id"])
		}

		Notification notification = notificationService.findNotification(params.id, getProvider(params))

        Notification updatedNotification = notificationUpdateService.update(notification, [enabled: false])
        if (updatedNotification.hasErrors()) return apiResponseBuilderService.buildErrorList(updatedNotification)

		return apiResponseBuilderService.buildSuccess(build(updatedNotification))
	}

	def build(notification) {
		def model = [:]

		model.object	   			  = 'notification'
		model.id                      = notification.publicId
		model.customer                = notification.customerAccount.publicId
		model.enabled                 = notification.enabled
		model.emailEnabledForProvider = Utils.toBoolean(notification.emailEnabledForProvider)
		model.smsEnabledForProvider   = Utils.toBoolean(notification.smsEnabledForProvider)
		model.emailEnabledForCustomer = Utils.toBoolean(notification.emailEnabledForCustomer)
		model.smsEnabledForCustomer   = Utils.toBoolean(notification.smsEnabledForCustomer)
		model.phoneCallEnabledForCustomer = Utils.toBoolean(notification.phoneCallEnabledForCustomer)

        if (notification.customerAccount.provider.whatsAppNotificationEnabled()) {
            model.whatsappEnabledForCustomer = Utils.toBoolean(notification.whatsappEnabledForCustomer)
        }

		if (notification.trigger) {
			switch(notification.trigger.event) {
				case NotificationEvent.CUSTOMER_PAYMENT_RECEIVED:
					model.event = NotificationEvent.PAYMENT_RECEIVED.toString()
					break
				case NotificationEvent.CUSTOMER_PAYMENT_OVERDUE:
					model.event = NotificationEvent.PAYMENT_OVERDUE.toString()
					break
				default:
					model.event = notification.trigger.event.toString()
					break
			}

			if (ApiMobileUtils.isMobileAppRequest()) {
				model.smsAllowedForProvider = notification.customerAccount.provider.mobilePhone ? true : false
				model.emailAllowedForProvider = notification.customerAccount.provider.email ? true : false
                model.schedule = notification.trigger.schedule.toString()
                model.updateBlocked = notification.updateBlocked
			}

			model.scheduleOffset = notification.trigger.scheduleOffset
		}

		model.deleted = Utils.toBoolean(notification.deleted)

		return model
	}

	private Map parseFields(Map params) {
        Map fields = [:]
        if (params.containsKey("id")) fields.id = params.id
        if (params.containsKey("event")) fields.event = parseEventFromApi(params.event.trim())
        if (params.containsKey("provider")) fields.provider = getProvider(params)
        if (params.containsKey("customer")) fields.customer = getCustomer(params)
        if (params.containsKey("emailEnabledForProvider")) fields.emailEnabledForProvider = params.emailEnabledForProvider
        if (params.containsKey("smsEnabledForProvider")) fields.smsEnabledForProvider = params.smsEnabledForProvider
        if (params.containsKey("emailEnabledForCustomer")) fields.emailEnabledForCustomer = params.emailEnabledForCustomer
        if (params.containsKey("smsEnabledForCustomer")) fields.smsEnabledForCustomer = params.smsEnabledForCustomer
        if (params.containsKey("phoneCallEnabledForCustomer")) fields.phoneCallEnabledForCustomer = params.phoneCallEnabledForCustomer
        if (params.containsKey("whatsappEnabledForCustomer")) fields.whatsappEnabledForCustomer = params.whatsappEnabledForCustomer
        if (params.containsKey("scheduleOffset")) fields.scheduleOffset = ApiBaseParser.tryParseInteger(params, "scheduleOffset")
        if (params.containsKey("enabled")) fields.enabled = params.enabled

        return fields
	}

    private NotificationEvent parseEventFromApi(String event) {
		if (event == "PAYMENT_RECEIVED") {
			return NotificationEvent.CUSTOMER_PAYMENT_RECEIVED
		} else if (event == "PAYMENT_OVERDUE") {
			return NotificationEvent.CUSTOMER_PAYMENT_OVERDUE
		} else {
			return NotificationEvent.convert(params.event)
		}
	}

	def setCustomerAccountIntoParams(params) {
		if (getCustomer(params)) {
			try {
				setCustomer(params, CustomerAccount.find(getCustomer(params), getProvider(params)).id)
			} catch (Exception e) {
				setCustomer(params, Long.valueOf(-1))
			}
		}
	}
}
