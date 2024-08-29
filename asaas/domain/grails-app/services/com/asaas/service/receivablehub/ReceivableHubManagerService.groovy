package com.asaas.service.receivablehub

import com.asaas.integration.sqs.SqsManager
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry

@GrailsCompileStatic
@Transactional
class ReceivableHubManagerService {

    public List<Long> sendPaymentOutboxMessages(List<Map> outboxItemList) {
        if (outboxItemList.isEmpty()) return []

        List<SendMessageBatchRequestEntry> messagesList = []
        for (Map outboxItem : outboxItemList) {
            String messageId = outboxItem.id.toString()
            messagesList.add(
                SendMessageBatchRequestEntry
                    .builder()
                    .id(messageId)
                    .messageDeduplicationId(messageId)
                    .messageGroupId(outboxItem.paymentId.toString())
                    .messageBody(outboxItem.payload.toString())
                    .messageAttributes([
                        "eventName": MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(outboxItem.eventName.toString())
                            .build()
                    ])
                    .build()
            )
        }

        SqsManager sqsManager = new SqsManager("receivableHubPayment")
        List<SendMessageBatchResponse> batchesResponseList = sqsManager.sendMessagesListInBatches(messagesList)

        List<Long> successfulMessagesIdList = batchesResponseList*.successful().flatten(({ SendMessageBatchResultEntry it -> Long.valueOf(it.id()) } as Closure<? extends List<SendMessageBatchResultEntry>>)) as List<Long>
        return successfulMessagesIdList
    }
}
