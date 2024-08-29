package com.asaas.service.sms

import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.sms.SmsMessage
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationPriority
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.async.Promise
import grails.transaction.Transactional

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

@Transactional
class SmsMessageService {

    def notificationRequestStatusService
    def smsSenderService

    public SmsMessage save(String message, String fromName, String phoneNumber, NotificationRequest notificationRequest) {
        SmsMessage smsMessage = new SmsMessage()
        smsMessage.message = message
        smsMessage.fromName = fromName
        smsMessage.phoneNumber = Utils.removeNonNumeric(phoneNumber)
        smsMessage.notificationRequest = notificationRequest

        smsMessage.save(flush: true, failOnError: true)
    }

    public void processPending() {
        final Date now = new Date()
        final Date smsWithoutHighPriorityStartingTime = CustomDateUtils.setTime(now, NotificationRequest.MOBILE_PHONE_WITHOUT_HIGH_PRIORITY_STARTING_HOUR, 0, 0)

        Map queryParams = [:]
        queryParams.column = "id"
        if (now < smsWithoutHighPriorityStartingTime) queryParams."notificationRequest.priority" = NotificationPriority.HIGH.priorityInt()

        List<Long> messageIdList = SmsMessage.nextOnQueue(queryParams).list(max: 240)
        if (!messageIdList) return

        updateToProcessing(messageIdList)

        List<Promise> promiseList = []

        messageIdList.collate(6).each {
            List items = it.collect()
            promiseList << task { sendList(items) }
        }
        waitAll(promiseList)
    }

    private void updateToProcessing(List<Long> messageList) {
        SmsMessage.withNewTransaction { status ->
            SmsMessage.executeUpdate("UPDATE SmsMessage SET version = version + 1, status = :status, lastUpdated = :lastUpdated WHERE id IN (:ids)", [status: Status.PROCESSING, lastUpdated: new Date(), ids: messageList])
        }
    }

    private void sendList(List<Long> smsMessageIdList) {
        List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList = []

        Utils.withNewTransactionAndRollbackOnError({
            for (Long smsMessageId in smsMessageIdList) {
                try {
                    SmsMessage smsMessage = SmsMessage.get(smsMessageId)

                    smsMessage.attempts = smsMessage.attempts + 1
                    smsMessage.save(failOnError: true)
                    Boolean sent = smsSenderService.send(smsMessage.message, smsMessage.phoneNumber, false, [
                        id: "SMS_MESSAGE_${smsMessage.id}_${smsMessage.attempts}",
                        smsMessageProvider: smsMessage.getSmsProvider(),
                        from: smsMessage.fromName
                    ])

                    if (sent) {
                        updateSendingStatusVOList.add(notificationRequestStatusService.buildSentObject(smsMessage.notificationRequestId, null, true))
                        smsMessage.status = Status.SENT
                    } else {
                        updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(smsMessage.notificationRequestId, null, true))
                        smsMessage.status = Status.ERROR
                    }

                    smsMessage.save(failOnError: true)
                } catch (Throwable exception) {
                    try {
                        SmsMessage smsMessageWithError = SmsMessage.get(smsMessageId)
                        smsMessageWithError.attempts = smsMessageWithError.attempts + 1
                        smsMessageWithError.status = Status.ERROR
                        smsMessageWithError.save(failOnError: true)

                        updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(smsMessageWithError.notificationRequestId, null, false))
                    } catch (Throwable exceptionInSetError) {
                        AsaasLogger.error("SmsMessageService.sendList >> Erro inesperado no SmsMessage [${ smsMessageId }]", exceptionInSetError)
                    }
                }
            }
        })

        notificationRequestStatusService.saveAsyncUpdateSendingStatusList(updateSendingStatusVOList)
    }
}
