package com.asaas.service.notification

import com.asaas.domain.payment.Payment
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.dto.NotificationSentDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.springframework.dao.DataIntegrityViolationException
import software.amazon.awssdk.services.sqs.model.Message

@Transactional
class PaymentNotificationSentQueueService {

    PaymentNotificationSentService paymentNotificationSentService

    public void processQueue() {
        SqsManager sqsManager = new SqsManager("preparedNotification")
        final Integer maxItems = 500
        final Integer timeoutToReceiveMessagesInSeconds = 10
        List<Message> messageList = sqsManager.receiveMessages(maxItems, timeoutToReceiveMessagesInSeconds)

        if (!messageList) return

        final Integer flushEvery = 50
        final Integer minItemsPerThread = 50

        ThreadUtils.processWithThreadsOnDemand(messageList, minItemsPerThread, { List<Message> sqsMessageSubList ->
            List<Message> sqsMessageToDeleteList = []

            Utils.forEachWithFlushSession(sqsMessageSubList, flushEvery, { Message sqsMessage ->
                Boolean processedMessage = true
                Utils.withNewTransactionAndRollbackOnError({
                    process(sqsMessage)
                }, [
                    ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (exception instanceof DataIntegrityViolationException && exception.getCause().getCause().getMessage().contains("payment_notification_sent.external_id")) {
                            AsaasLogger.warn("${this.getClass().getSimpleName()}.processQueue >> Ocorreu uma violação de constraint durante o processamento da mensagem [${sqsMessage.messageId()}]", exception)
                            return
                        }

                        processedMessage = false
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("${this.getClass().getSimpleName()}.processQueue >> Ocorreu um Lock durante o processamento da mensagem [${sqsMessage.messageId()}]", exception)
                            return
                        }

                        AsaasLogger.error("${this.getClass().getSimpleName()}.processQueue >> MessageId: [${sqsMessage.messageId()}, ignored: ${!processedMessage}]", exception)
                    }
                ])

                if (processedMessage) sqsMessageToDeleteList.add(sqsMessage)
            })

            if (sqsMessageToDeleteList) sqsManager.deleteBatch(sqsMessageToDeleteList)
        })
    }

    private void process(Message message) {
        NotificationSentDTO notificationSentDTO = GsonBuilderUtils.buildClassFromJson(message.body(), NotificationSentDTO)
        paymentNotificationSentService.save(Payment.read(notificationSentDTO.paymentId), notificationSentDTO.method, notificationSentDTO.id)
    }
}
