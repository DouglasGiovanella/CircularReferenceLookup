package com.asaas.service.notification

import com.asaas.integration.instanttextmessage.twilio.adapter.TwilioWhatsAppNotificationWebhookDTO
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.NewRelicUtils
import com.asaas.utils.ThreadUtils
import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.Message

@Transactional
class InstantTextMessageTwilioWhatsAppStatusService {

    private static final String SQS_QUEUE_CONFIG_KEY = "instantTextMessageWhatsappTwilio"

    def instantTextMessageUpdateRequestService

    public void processPendingNotificationList() {
        final Integer maxNumberOfMessages = 500
        final Integer timeoutToReceiveMessagesInSeconds = 10
        SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
        List<Message> sqsMessageList = []

        NewRelicUtils.registerMetric("twilioInstantTextMessageWhatsApp/receive", {
            sqsMessageList = sqsManager.receiveMessages(maxNumberOfMessages, timeoutToReceiveMessagesInSeconds)
        })

        NewRelicUtils.recordMetric("Custom/twilioInstantTextMessageWhatsApp/numberOfReceivedMessages", sqsMessageList.size().toFloat())

        if (!sqsMessageList) return

        final Integer threadSize = 250

        ThreadUtils.processWithThreadsOnDemand(sqsMessageList, threadSize, { List<Message> sqsMessageSubList ->
            try {
                List<TwilioWhatsAppNotificationWebhookDTO> twilioWhatsAppNotificationWebhookDTOList = []

                for (Message message : sqsMessageSubList) {
                    TwilioWhatsAppNotificationWebhookDTO twilioWhatsAppNotificationWebhookDTO = GsonBuilderUtils.buildClassFromJson(message.body, TwilioWhatsAppNotificationWebhookDTO)
                    twilioWhatsAppNotificationWebhookDTOList.add(twilioWhatsAppNotificationWebhookDTO)
                }

                instantTextMessageUpdateRequestService.saveInBatch(twilioWhatsAppNotificationWebhookDTOList)

                NewRelicUtils.registerMetric("twilioInstantTextMessageWhatsApp/delete", {
                    sqsManager.deleteBatch(sqsMessageSubList)
                })
            } catch (Exception exception) {
                AsaasLogger.error("InstantTextMessageTwilioWhatsAppStatusService.processPendingNotificationList >> Erro ao processar batch de mensagens")
                throw exception
            }
        })
    }
}
