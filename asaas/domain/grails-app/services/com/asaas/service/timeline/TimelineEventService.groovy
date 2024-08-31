package com.asaas.service.timeline

import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.notification.PhoneCallNotification
import com.asaas.domain.payment.Payment
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.timeline.TimelineEvent
import com.asaas.domain.timeline.TimelinePhoneCallInteraction
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationStatus
import com.asaas.timeline.TimelineEventType
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.FormUtils

import grails.transaction.Transactional

import org.hibernate.criterion.CriteriaSpecification

@Transactional
class TimelineEventService {

    def dataSource
    def notificationHistoryDescriptionService

	def list(Long customerAccountId, Long providerId, Integer max, Integer offset, Map search) {
		def list = TimelineEvent.createCriteria().list(sort: "id", order: "desc", max: max, offset: offset) {
			createAlias('customerAccount','customerAccount')
			createAlias('customerAccount.provider','provider')
			createAlias('notificationRequest','notificationRequest', CriteriaSpecification.LEFT_JOIN)

			and { eq("deleted", false) }

			if (providerId) {
				and { eq("provider.id", providerId) }
			}

			if (customerAccountId){
				and { eq("customerAccount.id", customerAccountId) }
			}

			if (search.paymentId) {
				and { eq("payment.id", search.paymentId) }
			}

			if (search.typeList) {
				or { search.typeList.each { eq("type", TimelineEventType.valueOf(it.toString())) } }
			}
		}

		return list
	}

    public void createNotificationEvent(NotificationStatus notificationStatus, NotificationRequest notificationRequest, NotificationRequestTo notificationRequestTo, String failReason, Boolean throwExceptionOnError = false) {
        try {
            String description = notificationHistoryDescriptionService.buildNotificationEventDescription(notificationStatus, notificationRequest, notificationRequestTo, failReason)
            TimelineEventType type = notificationStatus.isSent() ? TimelineEventType.NOTIFICATION_SUCCESS : TimelineEventType.NOTIFICATION_FAILURE

            buildAndSaveTimelineEvent(notificationRequest.payment, description, type, notificationRequest, notificationRequestTo)
        } catch (Exception exception) {
            if (throwExceptionOnError) throw exception

            AsaasLogger.error("TimelineEventService.createNotificationEvent >> Erro ao salvar TimelineEvent para o NotificationRequest [${notificationRequest.id}]", exception)
        }
    }

    public TimelineEvent buildNotificationEventObject(NotificationStatus notificationStatus, NotificationRequest notificationRequest, NotificationRequestTo notificationRequestTo, String failReason) {
        String description = notificationHistoryDescriptionService.buildNotificationEventDescription(notificationStatus, notificationRequest, notificationRequestTo, failReason)
        TimelineEventType type = notificationStatus.isSent() ? TimelineEventType.NOTIFICATION_SUCCESS : TimelineEventType.NOTIFICATION_FAILURE

        TimelineEvent timelineEvent = new TimelineEvent()
        timelineEvent.payment = notificationRequest.payment
        timelineEvent.customerAccount = notificationRequest.customerAccount
        timelineEvent.type = type
        timelineEvent.description = description
        timelineEvent.notificationRequest = notificationRequest
        timelineEvent.notificationRequestTo = notificationRequestTo

        return timelineEvent
    }

    public void saveInBatch(List<TimelineEvent> timelineEventList) {
        List<Map> dataToInsert = timelineEventList.collect { TimelineEvent it -> it.toDataMap() }

        final Integer maxItemsPerBatch = 50
        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "timeline_event", dataToInsert, maxItemsPerBatch)
    }

	private TimelineEvent buildAndSaveTimelineEvent(Payment payment, String description, TimelineEventType type, NotificationRequest notificationRequest, NotificationRequestTo notificationRequestTo) {
		TimelineEvent timelineEvent = new TimelineEvent()
		timelineEvent.customerAccount = payment.customerAccount
		timelineEvent.description = description
		timelineEvent.type = type
		timelineEvent.notificationRequest = notificationRequest
		timelineEvent.notificationRequestTo = notificationRequestTo
		timelineEvent.payment = payment

		timelineEvent.save(flush: true, failOnError: true)
        return timelineEvent
	}

	public void createUnexpectedValueReceivedEvent(Payment payment) {
		try {
			String description =  "Recebida confirmação de pagamento com valor diferente do esperado. Valor recebido: ${FormUtils.formatCurrencyWithMonetarySymbol(payment.value)}. Valor esperado: ${FormUtils.formatCurrencyWithMonetarySymbol(payment.getOriginalValueWithInterest())}."
			buildAndSaveTimelineEvent(payment, description, TimelineEventType.PAYMENT_UNEXPECTED_VALUE, null, null)
		} catch (Exception exception) {
            AsaasLogger.error("TimelineEventService.createUnexpectedValueReceivedEvent >> Erro ao criar evento de valor inesperado recebido para o pagamento. [paymentId: ${payment?.id}]", exception)
		}
	}

	public void createPaymentPostalServiceSentEvent(Payment payment, Date estimatedDeliveryDate) {
		try {
			String description = "A cobrança número ${payment.id} foi enviada via Correios. A estimativa de entrega é ${estimatedDeliveryDate.format('dd/MM/yyyy')}"

			buildAndSaveTimelineEvent(payment, description, TimelineEventType.PAYMENT_POSTAL_SERVICE_SENT, null, null)
		} catch (Exception exception) {
            AsaasLogger.error("TimelineEventService.createPaymentPostalServiceSentEvent >> Erro ao criar evento de envio de serviço postal para o pagamento. [paymentId: ${payment?.id}]", exception)
		}
	}

	public void createRefundRequestCreatedEvent(RefundRequest refundRequest) {
		try {
			String description = notificationHistoryDescriptionService.getRefundRequestCreatedNotificationDescription(refundRequest.payment)

			buildAndSaveTimelineEvent(refundRequest.payment, description, TimelineEventType.REFUND_REQUEST_CREATED, null, null)
		} catch (Exception exception) {
            AsaasLogger.error("TimelineEventService.createRefundRequestCreatedEvent >> Erro ao criar evento de criação de solicitação de reembolso. [refundRequestId: ${refundRequest?.id}]", exception)
		}
	}

	public void createRefundRequestExpiredEvent(RefundRequest refundRequest) {
		try {
			String description = notificationHistoryDescriptionService.getRefundRequestExpiredNotificationDescription(refundRequest.payment)

			buildAndSaveTimelineEvent(refundRequest.payment, description, TimelineEventType.REFUND_REQUEST_EXPIRED, null, null)
		} catch (Exception exception) {
            AsaasLogger.error("TimelineEventService.createRefundRequestExpiredEvent >> Erro ao criar evento de expiração de solicitação de reembolso. [refundRequestId: ${refundRequest?.id}]", exception)
		}
	}

    public void saveTimelinePhoneCallInteractionForEndPhoneCall(NotificationRequest notificationRequest, PhoneCallNotification phoneCallNotification) {
        TimelinePhoneCallInteraction timelinePhoneCallInteraction = TimelinePhoneCallInteraction.findOrCreateWhere(phoneCallNotification: phoneCallNotification)
        timelinePhoneCallInteraction.phoneCallStatus = phoneCallNotification.externalStatus.convertToTimelineEndPhoneCallStatus()
        timelinePhoneCallInteraction.startDate = phoneCallNotification.startDate

        if (!timelinePhoneCallInteraction.timelineEvent) {
            TimelineEventType type = phoneCallNotification.externalStatus.convertToTimelineEventType()
            String phoneNumber = phoneCallNotification.destinationPhoneNumber
            String description = notificationHistoryDescriptionService.getPhoneCallInteractionNotificationDescription(phoneNumber, notificationRequest.payment.getInvoiceNumber())
            timelinePhoneCallInteraction.timelineEvent = buildAndSaveTimelineEvent(notificationRequest.payment, description, type, notificationRequest, null)
        }

        timelinePhoneCallInteraction.save(flush: true, failOnError: true)
    }

    public void saveTimelinePhoneCallInteractionForCustomerInteraction(NotificationRequest notificationRequest, PhoneCallNotification phoneCallNotification) {
        TimelinePhoneCallInteraction timelinePhoneCallInteraction = TimelinePhoneCallInteraction.findOrCreateWhere(phoneCallNotification: phoneCallNotification)
        timelinePhoneCallInteraction.listenedLinhaDigitavel = phoneCallNotification.listenedLinhaDigitavel
        if (phoneCallNotification.lastCustomerInteraction) {
            timelinePhoneCallInteraction.phoneCallCustomerAccountInteraction = phoneCallNotification.lastCustomerInteraction.convertToTimelinePhoneCallCustomerAccountInteraction()
        }
        timelinePhoneCallInteraction.startDate = phoneCallNotification.startDate

        TimelineEventType type = phoneCallNotification.status.convertToTimelineEventType()
        String phoneNumber = notificationRequest.getNotificationPhoneNumber()
        String description = notificationHistoryDescriptionService.getPhoneCallInteractionNotificationDescription(phoneNumber, notificationRequest.payment.getInvoiceNumber())
        timelinePhoneCallInteraction.timelineEvent = buildAndSaveTimelineEvent(notificationRequest.payment, description, type, notificationRequest, null)

        timelinePhoneCallInteraction.save(flush: true, failOnError: true)
    }
}
