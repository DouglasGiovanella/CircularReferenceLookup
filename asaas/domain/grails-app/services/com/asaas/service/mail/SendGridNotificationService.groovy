package com.asaas.service.mail

import com.asaas.customer.BaseCustomer
import com.asaas.domain.customer.Customer
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.notification.NotificationRequestToEvent
import com.asaas.email.AsaasEmailEvent
import com.asaas.email.SendGridNotificationEvent
import com.asaas.exception.SmsFailException
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationReceiver
import com.asaas.sendgrid.dto.SendGridNotificationSqsReceivedMessageDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.NewRelicUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.Message

import java.util.concurrent.ConcurrentHashMap

@Transactional
class SendGridNotificationService {

    private static final String SQS_QUEUE_CONFIG_KEY = "sendGridNotification"

    def customerMessageService
    def notificationRequestToEventService
    def notificationRequestViewingInfoService
    def smsSenderService

    public void processPendingNotificationList() {
        final Integer maxNumberOfMessages = 1000
        final Integer timeoutToReceiveMessagesInSeconds = 10
        SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
        List<Message> sqsMessageList = []

        NewRelicUtils.registerMetric("sendGridNotification/receive", {
            sqsMessageList = sqsManager.receiveMessages(maxNumberOfMessages, timeoutToReceiveMessagesInSeconds)
        })

        NewRelicUtils.recordMetric("Custom/sendGridNotification/numberOfReceivedMessages", sqsMessageList.size().toFloat())

        if (!sqsMessageList) return

        final Integer flushEvery = 50
        final Integer threadSize = 125

        Map notificationRequestSendGridMessageDataMap = buildNotificationRequestSendGridMessageDataMap(sqsMessageList)
        ThreadUtils.processWithThreadsOnDemand(notificationRequestSendGridMessageDataMap.keySet().toList(), threadSize, { List<String> notificationRequestIdList ->
            List<Message> sqsMessageToDeleteList = []

            Utils.forEachWithFlushSession(notificationRequestIdList, flushEvery, { String notificationRequestId ->
                for (Map sendGridMessageDataMap : notificationRequestSendGridMessageDataMap[notificationRequestId]) {
                    Message sqsMessage = sendGridMessageDataMap.sqsMessage
                    Boolean hasErrors = false

                    Utils.withNewTransactionAndRollbackOnError({
                        processSendGridNotification(sendGridMessageDataMap.sendGridMessageDTO)
                    }, [ignoreStackTrace: true, onError: { Exception exception ->
                        hasErrors = true
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("SendGridNotificationService.processPendingNotificationList >> Ocorreu um Lock durante o processamento da mensagem [${sqsMessage.messageId}]", exception)
                            return
                        }

                        AsaasLogger.error("SendGridNotificationService.processPendingNotificationList >> MessageId: [${sqsMessage.messageId}]", exception)
                    }])

                    if (!hasErrors) sqsMessageToDeleteList.add(sqsMessage)
                }
            })

            if (sqsMessageToDeleteList) {
                NewRelicUtils.registerMetric("sendGridNotification/delete", {
                    sqsManager.deleteBatch(sqsMessageToDeleteList)
                })
            }
        })
    }

    private void processSendGridNotification(SendGridNotificationSqsReceivedMessageDTO sendGridNotificationSqsReceivedMessageDTO) {
        NotificationRequestTo notificationRequestTo = NotificationRequestTo.get(sendGridNotificationSqsReceivedMessageDTO.notificationRequestToId)
        if (!notificationRequestTo) return

        AsaasEmailEvent asaasEmailEvent = parseSendGridEvent(sendGridNotificationSqsReceivedMessageDTO.getEvent(), sendGridNotificationSqsReceivedMessageDTO.url)

        if (shouldManageEvent(sendGridNotificationSqsReceivedMessageDTO.email, asaasEmailEvent, notificationRequestTo)) {
            NotificationRequestToEvent notificationRequestToEvent = notificationRequestToEventService.saveOrUpdate(notificationRequestTo, asaasEmailEvent, sendGridNotificationSqsReceivedMessageDTO.buildEventDate())

            if (asaasEmailEvent.isOpen() && !notificationRequestToEvent.notificationRequestTo.notificationRequest.viewed) {
                notificationRequestViewingInfoService.saveNotificationViewedIfNecessary(notificationRequestToEvent.notificationRequestTo.notificationRequest, sendGridNotificationSqsReceivedMessageDTO.buildEventDate())
            }

            if (AsaasEmailEvent.errorAndSpamStatusList.contains(asaasEmailEvent)) {
                notifyAboutCustomerAccountEmailDeliveryFail(notificationRequestToEvent, sendGridNotificationSqsReceivedMessageDTO.reason)
                notifyAboutEmailDeliveryFail(notificationRequestToEvent)
            }
        }
    }

    private AsaasEmailEvent parseSendGridEvent(String event, String url) {
        if (SendGridNotificationEvent.valueOf(event) == SendGridNotificationEvent.CLICK) {
            if (url?.indexOf("/i/") > 0) {
                return AsaasEmailEvent.INVOICE_CLICK
            }
        } else {
            return SendGridNotificationEvent.valueOf(event).asaasEmailEvent
        }

        return null
    }

    private void notifyAboutCustomerAccountEmailDeliveryFail(NotificationRequestToEvent notificationRequestToEvent, String reason) {
        if (notificationRequestToEvent.notificationRequestTo.notificationRequest.receiver != NotificationReceiver.CUSTOMER) return

        String reasonOfFailMessage
        if (AsaasEmailEvent.errorStatusList.contains(notificationRequestToEvent.event)) {
            reasonOfFailMessage = buildReasonEmailDeliveryFail(notificationRequestToEvent, reason)
        }

        customerMessageService.notifyAboutEmailDeliveryFailByEmail(notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount.provider, notificationRequestToEvent, reasonOfFailMessage)

        if (!notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount.mobilePhone) return

        notifyAboutEmailDeliveryFailBySms(notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount.provider, notificationRequestToEvent)
    }

    private void notifyAboutEmailDeliveryFail(NotificationRequestToEvent notificationRequestToEvent) {
        if (notificationRequestToEvent.notificationRequestTo.notificationRequest.receiver != NotificationReceiver.PROVIDER) return

        if (!notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount.provider.mobilePhone) return

        notifyAboutEmailDeliveryFailBySms(notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount.provider, notificationRequestToEvent)
    }

    private Boolean shouldManageEvent(String email, AsaasEmailEvent asaasEmailEvent, NotificationRequestTo notificationRequestTo) {
        if (!asaasEmailEvent) return false
        if (!notificationRequestTo.email.equalsIgnoreCase(email)) return false
        if (notificationAlreadyProcessed(asaasEmailEvent, notificationRequestTo)) return false
        if (asaasEmailEvent.isDeferred()) return false

        return true
    }

	private Boolean notificationAlreadyProcessed(AsaasEmailEvent event, NotificationRequestTo notificationRequestTo) {
		if (!(event in AsaasEmailEvent.errorAndSpamStatusList)) return false

		if (NotificationRequestToEvent.findWhere(notificationRequestTo: notificationRequestTo, event: event)) {
			return true
		} else {
			return false
		}
	}

    private void notifyAboutEmailDeliveryFailBySms(Customer customer, NotificationRequestToEvent notificationRequestToEvent) {
        if (customer.highDefaultRate) return

        NotificationReceiver notificationReceiver = notificationRequestToEvent.notificationRequestTo.notificationRequest.receiver
        if (!notificationReceiver) return

        BaseCustomer receiverEntity = notificationReceiver.isProvider()
            ? customer
            : notificationRequestToEvent.notificationRequestTo.notificationRequest.customerAccount

        String errorMessageSuffix = "para ${receiverEntity.class.getSimpleName()}: [${receiverEntity.id}] com NotificationRequestToEvent: [${notificationRequestToEvent.id}]"

        if (!AsaasEmailEvent.errorStatusList.contains(notificationRequestToEvent.event)) {
            AsaasLogger.warn("SendGridNotificationService.notifyAboutEmailDeliveryFailBySms >>> Caso de falha no envio de email não tratado ${errorMessageSuffix}")
            return
        }

        try {
            Utils.withNewTransactionAndRollbackOnError({
                Map params = [
                    email: notificationRequestToEvent.notificationRequestTo.email,
                    name: customer.buildTradingName()
                ]

                String mobilePhone = receiverEntity.mobilePhone
                String template = notificationReceiver.isProvider()
                    ? "Olá! Seu e-mail #{email} parece inválido. Aconselhamos atualizar seu e-mail acessando a área Minha Conta."
                    : "Olá! Seu e-mail #{email} parece inválido. Aconselhamos atualizar seu e-mail contatando #{name}."

                String message = smsSenderService.buildMessage(template, params, ["email", "name"])

                customerMessageService.sendSmsText(customer, message, mobilePhone, true)
            }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
        } catch (SmsFailException smsFailException) {
            AsaasLogger.warn("SendGridNotificationService.notifyAboutEmailDeliveryFailBySms >>> SmsFailException ${errorMessageSuffix}", smsFailException)
        } catch (Exception exception) {
            AsaasLogger.error("SendGridNotificationService.notifyAboutEmailDeliveryFailBySms >>> Erro ao enviar SMS sobre falha na entrega de e-mail ${errorMessageSuffix}", exception)
        }
    }

    private String buildReasonEmailDeliveryFail(NotificationRequestToEvent notificationRequestToEvent, String sendGridReason) {
        if (!sendGridReason) return

        if (sendGridReason.startsWith("421 4.7.1")) return "o endereço de email ${notificationRequestToEvent.notificationRequestTo.email} está indisponível"

        if (sendGridReason.startsWith("450 4.2.1")) return "o email ${notificationRequestToEvent.notificationRequestTo.email} de seu cliente parece estar desabilitado ou não aceitando mensagens"

        if (sendGridReason.startsWith("501")) return

        if (sendGridReason.startsWith("503")) return "o serviço de email do destinatário rejeitou nossa mensagem"

        if (sendGridReason.startsWith("550")) {
            if (sendGridReason.contains("5.1.0") || sendGridReason.contains("5.1.1") || sendGridReason.contains(" No Such User") || sendGridReason.contains("Unknown User")) return

            if (sendGridReason.contains("5.2.1")) return "o email ${notificationRequestToEvent.notificationRequestTo.email} está desabilitado"

            if (sendGridReason.contains("Mailbox quota exceeded")) return "a caixa de entrada do email ${notificationRequestToEvent.notificationRequestTo.email} está cheia"

            return "o email ${notificationRequestToEvent.notificationRequestTo.email} não existe ou está temporariamente indisponível"
        }

        if (sendGridReason.startsWith("552 5.2.2")) return "a caixa de entrada do email ${notificationRequestToEvent.notificationRequestTo.email} está cheia"

        if (sendGridReason.startsWith("554")) {
            if (sendGridReason.startsWith("554 5.1.1")) return

            if (sendGridReason.contains("5.7.1") || sendGridReason.contains("access denied") || sendGridReason.contains("delivery not authorized")) return "nosso email foi rejeitado pelo destinatário"
        }

        if (sendGridReason.contains("considered spam") || sendGridReason.contains("Spam Reporting Address")) return "nosso email caiu no filtro de spam"

        if (sendGridReason.contains("connection refused") || sendGridReason.contains("permanently rejected message") || sendGridReason.contains("time out")) return "nosso email foi rejeitado pelo destinatário"

        return
    }

    private Map buildNotificationRequestSendGridMessageDataMap(List<Message> sqsMessageList) {
        Map notificationRequestSendGridMessageDTODataMap = new ConcurrentHashMap()
        for (Message sqsMessage : sqsMessageList) {
            SendGridNotificationSqsReceivedMessageDTO sendGridMessageDTO = GsonBuilderUtils.buildClassFromJson(sqsMessage.body, SendGridNotificationSqsReceivedMessageDTO)

            List<Map> sendGridMessageDTOList = notificationRequestSendGridMessageDTODataMap[sendGridMessageDTO.notificationId]
            if (!sendGridMessageDTOList) {
                sendGridMessageDTOList = []
                notificationRequestSendGridMessageDTODataMap[sendGridMessageDTO.notificationId] = sendGridMessageDTOList
            }

            sendGridMessageDTOList.add([
                sqsMessage: sqsMessage,
                sendGridMessageDTO: sendGridMessageDTO,
            ])
        }

        return notificationRequestSendGridMessageDTODataMap
    }
}
