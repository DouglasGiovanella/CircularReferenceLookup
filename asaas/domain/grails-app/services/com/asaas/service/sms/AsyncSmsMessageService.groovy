package com.asaas.service.sms

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.sms.AsyncSmsMessage
import com.asaas.domain.sms.AsyncSmsMessageStatus
import com.asaas.domain.sms.AsyncSmsType
import com.asaas.domain.sms.SmsPriority
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsyncSmsMessageService {

    def smsSenderService

    public AsyncSmsMessage save(String message, String fromName, String phoneNumber, SmsPriority smsPriority, AsyncSmsType asyncSmsType) {
        AsyncSmsMessage asyncSmsMessage = new AsyncSmsMessage()
        asyncSmsMessage.message = message
        asyncSmsMessage.fromName = fromName
        asyncSmsMessage.phoneNumber = Utils.removeNonNumeric(phoneNumber)
        asyncSmsMessage.type = asyncSmsType
        asyncSmsMessage.priority = smsPriority.value

        asyncSmsMessage.save(failOnError: true)

        return asyncSmsMessage
    }

    public void processPending(AsyncSmsType asyncSmsType) {
        final Integer limitItemsPerExec = 250
        final Integer minItemsPerThread = 50

        List<Long> messageList = AsyncSmsMessage.query([
            column: "id",
            status: AsyncSmsMessageStatus.PENDING,
            type: asyncSmsType,
            sortList: [
                [sort: "priority", order: "desc"],
                [sort: "id", order: "asc"]
            ]
        ]).list(max: limitItemsPerExec)

        if (!messageList) return

        ThreadUtils.processWithThreadsOnDemand(messageList, minItemsPerThread, { List<Long> idList ->
            sendList(idList)
        })
    }

    private void sendList(List<Long> messageList) {
        for (Long id in messageList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                send(id)
            }, [
                logErrorMessage: "AsyncSmsMessageService.sendList >> Erro ao enviar AsyncSmsMessage [${id}]",
                onError        : { hasError = true }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    resendIfNecessary(id)
                })
            }
        }
    }

    private void send(Long id) {
        AsyncSmsMessage asyncSmsMessage = AsyncSmsMessage.get(id)

        asyncSmsMessage.attempts = asyncSmsMessage.attempts + 1
        asyncSmsMessage.save(failOnError: true)

        Boolean sent = smsSenderService.send(asyncSmsMessage.message, asyncSmsMessage.phoneNumber, false, [
            id  : "ASYNC_SMS_MESSAGE_${asyncSmsMessage.id}_${asyncSmsMessage.attempts}",
            from: asyncSmsMessage.fromName
        ])

        asyncSmsMessage.status = sent ? AsyncSmsMessageStatus.SENT : AsyncSmsMessageStatus.ERROR

        asyncSmsMessage.save(failOnError: true)
    }

    private void resendIfNecessary(Long id) {
        AsyncSmsMessage asyncSmsMessageWithError = AsyncSmsMessage.get(id)
        asyncSmsMessageWithError.attempts = asyncSmsMessageWithError.attempts + 1
        asyncSmsMessageWithError.status = asyncSmsMessageWithError.attempts < AsaasApplicationHolder.config.asaas.sms.asyncSmsMessage.attempts.max ? AsyncSmsMessageStatus.PENDING : AsyncSmsMessageStatus.ERROR
        asyncSmsMessageWithError.save(failOnError: true)
    }
}
