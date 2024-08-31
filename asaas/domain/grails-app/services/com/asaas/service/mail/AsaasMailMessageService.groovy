package com.asaas.service.mail

import com.asaas.domain.asaasconfig.AsaasConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.email.AsaasMailMessageAttachment
import com.asaas.domain.email.AsaasMailMessageExternalNotificationTemplate
import com.asaas.domain.email.SendGridRequestLog
import com.asaas.domain.notification.NotificationRequest
import com.asaas.email.AsaasEmailEvent
import com.asaas.email.AsaasMailMessageStatus
import com.asaas.email.EventNotificationType
import com.asaas.email.asaasmailmessage.AsaasMailMessageVO
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationPriority
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.sendgrid.SendGridMailSender
import com.asaas.sendgrid.SendGridMailSender.Email as SendGridEmail
import com.asaas.sendgrid.SendGridMailSender.Response as SendGridResponse
import com.asaas.sendgrid.exception.SendGridException
import com.asaas.service.api.ApiBaseService
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.Utils
import grails.async.Promise
import grails.transaction.Transactional

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

@Transactional
class AsaasMailMessageService extends ApiBaseService {

    def asaasMailMessageExternalNotificationTemplateService
    def customerMailService
    def customerNotificationConfigService
    def dataSource
    def notificationRequestStatusService
    def sendGridManagerService

    public AsaasMailMessage save(AsaasMailMessageVO asaasMailMessageVO) {
        AsaasMailMessage asaasMailMessage = new AsaasMailMessage()

        asaasMailMessage.to = asaasMailMessageVO.to
        asaasMailMessage.replyTo = asaasMailMessageVO.replyTo
        asaasMailMessage.from = asaasMailMessageVO.from
        asaasMailMessage.fromName = asaasMailMessageVO.fromName
        asaasMailMessage.subject = asaasMailMessageVO.subject
        asaasMailMessage.text = asaasMailMessageVO.text
        asaasMailMessage.html = asaasMailMessageVO.isHtml
        asaasMailMessage.ccList = asaasMailMessageVO.ccList
        asaasMailMessage.bccList = asaasMailMessageVO.bccList
        asaasMailMessage.notificationRequest = asaasMailMessageVO.notificationRequest
        asaasMailMessage.notificationRequestTo = asaasMailMessageVO.notificationRequestTo

		asaasMailMessage.save(flush: true, failOnError: true)

        if (asaasMailMessageVO.externalTemplate) {
            asaasMailMessageExternalNotificationTemplateService.save(asaasMailMessage, asaasMailMessageVO)
        }

        for (AsaasMailMessageAttachment attachment : asaasMailMessageVO.attachmentList) {
            attachment.asaasMailMessage = asaasMailMessage
            attachment.save(failOnError: true)
        }

        return asaasMailMessage
    }

    public void processPending() {
        final Date now = new Date()
        final Date emailWithoutHighPriorityStartingTime = CustomDateUtils.setTime(now, NotificationRequest.EMAIL_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)

        Map queryParams = [:]
        queryParams.columnList = ["id", "notificationRequestTo.id"]
        queryParams.status = Status.PENDING
        queryParams.sortList = [[sort: "notificationRequest.priority", order: "desc"], [sort: "id", order: "asc"]]
        if (now < emailWithoutHighPriorityStartingTime) queryParams."notificationRequest.priority" = NotificationPriority.HIGH.priorityInt()

        List<Map> asaasMailMessageListMap = AsaasMailMessage.query(queryParams).list(max: 350)
        if (!asaasMailMessageListMap) return

        updateToProcessing(asaasMailMessageListMap)

        List<Long> asaasMailMessageIdList = asaasMailMessageListMap.collect { Long.valueOf(it.id) }

        List<Promise> promiseList = []
        asaasMailMessageIdList.collate(10).each {
            List idList = it.collect()
            promiseList << task { sendEmailList(idList) }
        }
        waitAll(promiseList)

        Boolean hasAnyAsaasMailMessageWithError = AsaasMailMessage.query([column: "id", "id[in]": asaasMailMessageIdList, status: AsaasMailMessageStatus.ERROR]).get().asBoolean()

        if (hasAnyAsaasMailMessageWithError) {
            AsaasLogger.error("AsaasMailMessageService.processPending >> Erro ao enviar AsaasMailMessage! Verifique o log do servidor de jobs para detalhes.")
        }
    }

    private void sendEmailList(List<Long> asaasMailMessageIdList) {
        List<Long> asaasMailMessageIdPendingList = []
        List<Long> asaasMailMessageIdSentList = []
        List<Long> asaasMailMessageIdErrorList = []
        List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList = []

        Utils.withNewTransactionAndRollbackOnError({
            for (Long asaasMailMessageId : asaasMailMessageIdList) {
                AsaasMailMessage asaasMailMessage = AsaasMailMessage.read(asaasMailMessageId)

                AsaasMailMessageStatus asaasMailMessageStatus

                try {
                    updateSendingStatusVOList.add(notificationRequestStatusService.buildSentObject(asaasMailMessage.notificationRequestId, asaasMailMessage.notificationRequestToId, true))

                    if (hasExternalNotificationTemplate(asaasMailMessage)) {
                        Boolean isMailSent = sendGridManagerService.sendMailFromTemplate(asaasMailMessage)
                        asaasMailMessageStatus = isMailSent ? AsaasMailMessageStatus.SENT : AsaasMailMessageStatus.ERROR
                    } else {
                        SendGridMailSender sender
                        Customer customer = asaasMailMessage.notificationRequest?.customerAccount?.provider

                        if (shouldUseApiKey(asaasMailMessage)) {
                            String apiKey = customerMailService.getEmailApiKey(customer)

                            sender = new SendGridMailSender(apiKey)
                        } else {
                            Map credentials = customerNotificationConfigService.getCustomerEmailProviderCredentials(customer)
                            sender = new SendGridMailSender(credentials.username.toString(), credentials.password.toString())
                        }

                        SendGridEmail email = buildSendGridEmail(asaasMailMessage)

                        SendGridResponse sendGridResponse = sender.send(email)
                        Date requestTime = sendGridResponse.requestTime
                        Date responseTime = sendGridResponse.responseTime

                        asaasMailMessageStatus = sendGridResponse.getStatus() ? AsaasMailMessageStatus.SENT : AsaasMailMessageStatus.ERROR

                        if (asaasMailMessageStatus.isError()) {
                            AsaasLogger.info("AsaasMailMessageService.sendEmailList > Não foi possível enviar AsaasMailMessage [${asaasMailMessage.id}] [${ sendGridResponse.message }]")
                        }

                        SendGridRequestLog sendGridRequestLog = new SendGridRequestLog()
                        sendGridRequestLog.asaasMailMessageId = asaasMailMessage.id
                        sendGridRequestLog.requestTime = requestTime
                        sendGridRequestLog.responseTime = responseTime
                        sendGridRequestLog.save()
                    }
                } catch (Exception exception) {
                    AsaasLogger.error("AsaasMailMessageService.sendEmailList > Error on AsaasMailMessage [${asaasMailMessageId}]", exception)

                    if (shouldRetryOnError(asaasMailMessageId, exception)) {
                        asaasMailMessageStatus = AsaasMailMessageStatus.PENDING
                    } else {
                        asaasMailMessageStatus = AsaasMailMessageStatus.ERROR
                    }
                } finally {
                    switch (asaasMailMessageStatus) {
                        case AsaasMailMessageStatus.SENT:
                            asaasMailMessageIdSentList.add(asaasMailMessageId)
                            break
                        case AsaasMailMessageStatus.ERROR:
                            asaasMailMessageIdErrorList.add(asaasMailMessageId)
                            break
                        case AsaasMailMessageStatus.PENDING:
                            asaasMailMessageIdPendingList.add(asaasMailMessageId)
                            break
                        default:
                            AsaasLogger.error("AsaasMailMessageService.sendEmailList > Error on AsaasMailMessage [${asaasMailMessageId}] Status ${asaasMailMessageStatus} não suportado!")

                            Utils.withNewTransactionAndRollbackOnError({
                                AsaasMailMessage asaasMailMessageWithError = AsaasMailMessage.get(asaasMailMessageId)
                                asaasMailMessageWithError.attempts = asaasMailMessage.attempts + 1
                                asaasMailMessageWithError.status = AsaasMailMessageStatus.ERROR
                                asaasMailMessageWithError.save(failOnError: true)
                            })
                    }
                }
            }
        })

        notificationRequestStatusService.saveAsyncUpdateSendingStatusList(updateSendingStatusVOList)

        if (asaasMailMessageIdSentList) updateSendStatus(asaasMailMessageIdSentList, AsaasMailMessageStatus.SENT)
        if (asaasMailMessageIdErrorList) updateSendStatus(asaasMailMessageIdErrorList, AsaasMailMessageStatus.ERROR)
        if (asaasMailMessageIdPendingList) updateSendStatus(asaasMailMessageIdPendingList, AsaasMailMessageStatus.PENDING)
	}

    private Boolean shouldRetryOnError(Long asaasMailMessageId, Exception exception) {
        AsaasMailMessage asaasMailMessage = AsaasMailMessage.read(asaasMailMessageId)
        if (!asaasMailMessage.status.isProcessing()) return false
        if (asaasMailMessage.attempts +1 > AsaasMailMessage.MAX_ATTEMPTS) return false

        if (exception instanceof SendGridException) return true
        if (exception instanceof RuntimeException) return true

        return false
    }

    private void updateSendStatus(List<Long> asaasMailMessageIdList, AsaasMailMessageStatus status) {
        if (!status.isSent() && !status.isError() && !status.isPending()) throw new RuntimeException("SmsMessageService.updateSendStatus > Status ${status} não suportado!")
        Utils.withNewTransactionAndRollbackOnError({
            AsaasMailMessage.executeUpdate("UPDATE AsaasMailMessage SET version = version + 1, attempts = attempts + 1, status = :status, lastUpdated = :lastUpdated WHERE id IN (:asaasMailMessageIdList)", [status: status, lastUpdated: new Date(), asaasMailMessageIdList: asaasMailMessageIdList])
        })
    }

    private void updateToProcessing(List<Map> asaasMailMessageListMap) {
        List<Long> asaasMailMessageIdList = asaasMailMessageListMap.collect { Long.valueOf(it.id) }
        List<Long> notificationRequestToIdList  = asaasMailMessageListMap.collect { Long.valueOf(it."notificationRequestTo.id") }

        Utils.withNewTransactionAndRollbackOnError({
            AsaasMailMessage.executeUpdate("UPDATE AsaasMailMessage SET version = version + 1, status = :status, lastUpdated = :lastUpdated WHERE id IN (:asaasMailMessageIdList)", [status: AsaasMailMessageStatus.PROCESSING, lastUpdated: new Date(), asaasMailMessageIdList: asaasMailMessageIdList])
        })

        List<Map> notificationRequestToEventToInsert = notificationRequestToIdList.collect { Long notificationRequestToId ->
            return [
                "version": 0,
                "event": AsaasEmailEvent.SENT.toString(),
                "event_date": new Date(),
                "notification_request_to_id": notificationRequestToId
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "notification_request_to_event", notificationRequestToEventToInsert)
    }

	private SendGridEmail buildSendGridEmail(AsaasMailMessage asaasMailMessage) {
		SendGridEmail email = new SendGridMailSender.Email()

		email.addTo(asaasMailMessage.to)

		if (asaasMailMessage.ccList) {
			email.addCc(asaasMailMessage.ccList)
		}

		if (asaasMailMessage.bccList) {
			email.addBcc(asaasMailMessage.bccList.toArray(new String[0]))
		}

		email.setFrom(asaasMailMessage.from)
		email.setFromName(asaasMailMessage.fromName)
		email.setReplyTo(asaasMailMessage.replyTo)
		email.setSubject(asaasMailMessage.subject)

		if (asaasMailMessage.html) {
			email.setHtml(asaasMailMessage.text)
		} else {
			email.setText(asaasMailMessage.text)
		}

		if (asaasMailMessage.notificationRequest) {
			email.addUniqueArg("notificationId", asaasMailMessage.notificationRequest.id.toString())
			email.addUniqueArg("notificationType", EventNotificationType.NOTIFICATION_REQUEST.toString())
			email.addUniqueArg("notificationRequestToId", String.valueOf(asaasMailMessage.notificationRequest.notificationRequestToList.findAll{ it.email == asaasMailMessage.to }[0].id))
		}

		if (asaasMailMessage.attachments) {
			asaasMailMessage.attachments.each { email.addAttachment(it.name, new ByteArrayInputStream(it.content)) }
		}

		return email
	}

    private Boolean shouldUseApiKey(AsaasMailMessage asaasMailMessage) {
        String encryptedEmailProviderApiKey = CustomerNotificationConfig.query([
            column: "encryptedEmailProviderApiKey",
            customerId: asaasMailMessage.notificationRequest.customerAccount.providerId
        ]).get()

        if (encryptedEmailProviderApiKey) return true

        if (!AsaasConfig.getInstance().sendGridUsingApiKey) return false

        return true
    }

    private Boolean hasExternalNotificationTemplate(AsaasMailMessage asaasMailMessage) {
        return AsaasMailMessageExternalNotificationTemplate.query([
            asaasMailMessageId: asaasMailMessage.id,
            column: "externalTemplate"
        ]).get().asBoolean()
    }
}
