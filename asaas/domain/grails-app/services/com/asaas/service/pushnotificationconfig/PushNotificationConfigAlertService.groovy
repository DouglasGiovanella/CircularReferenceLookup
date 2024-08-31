package com.asaas.service.pushnotificationconfig

import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigAlertQueue
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationConfigAlertType
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PushNotificationConfigAlertService {

    def customerAlertNotificationService
    def pushNotificationConfigAlertQueueService
    def pushNotificationMessageService

    public Boolean sendAlertsPushNotificationConfig(List<PushNotificationConfigAlertType> typeList) {
        final Integer maxSendsPerRequest = 100
        Date today = new Date().clearTime()

        Map query = [
            column: "id",
            sent: false,
            scheduleDate: today,
            "type[in]": typeList
        ]

        List<Long> pushNotificationConfigAlertQueueList = PushNotificationConfigAlertQueue.query(query).list([max: maxSendsPerRequest])

        executeList(pushNotificationConfigAlertQueueList)

        return pushNotificationConfigAlertQueueList.size() == maxSendsPerRequest
    }

    private void executeList(List<Long> pushNotificationConfigAlertQueueList) {
        final Integer maxItemsPerThread = 25

        ThreadUtils.processWithThreadsOnDemand(pushNotificationConfigAlertQueueList, maxItemsPerThread, { List<Long> idList ->
            Utils.forEachWithFlushSession(idList, 25, { Long pushNotificationConfigAlertQueueId ->
                Utils.withNewTransactionAndRollbackOnError({
                    PushNotificationConfigAlertQueue pushNotificationConfigAlertQueue = PushNotificationConfigAlertQueue.get(pushNotificationConfigAlertQueueId)

                    send(pushNotificationConfigAlertQueue)

                    pushNotificationConfigAlertQueue.sent = true
                    pushNotificationConfigAlertQueue.save(failOnError: true)
                }, [logErrorMessage: "PushNotificationConfigAlertService.executeList() -> erro ao enviar e-mail de alerta - pushNotificationConfigAlertQueue: ${pushNotificationConfigAlertQueueId}"])
            })
        })
    }

    private void send(PushNotificationConfigAlertQueue pushNotificationConfigAlertQueue) {
        if (pushNotificationConfigAlertQueue.type.isAttemptFail()) {
            sendNotificationAttemptFail(pushNotificationConfigAlertQueue.pushNotificationConfig, pushNotificationConfigAlertQueue.pushNotificationRequestAttempt)
        } else if (pushNotificationConfigAlertQueue.type.isQueueInterrupted()) {
            sendNotificationQueueInterrupted(pushNotificationConfigAlertQueue.pushNotificationConfig, pushNotificationConfigAlertQueue.pushNotificationRequestAttempt)
        } else if (pushNotificationConfigAlertQueue.type.isQueueInterruptedSevenDays()) {
            sendNotificationInterruptedByFailureSevenDaysAgo(pushNotificationConfigAlertQueue.pushNotificationConfig, pushNotificationConfigAlertQueue.pushNotificationRequestAttempt)
        } else if (pushNotificationConfigAlertQueue.type.isQueueInterruptedFourteenDays()) {
            sendNotificationInterruptedByFailureFourteenDaysAgo(pushNotificationConfigAlertQueue.pushNotificationConfig, pushNotificationConfigAlertQueue.pushNotificationRequestAttempt)
        }
    }

    private void sendNotificationAttemptFail(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        customerAlertNotificationService.notifyPushNotificationAttemptFail(pushNotificationConfig.provider, pushNotificationConfig)

        pushNotificationMessageService.sendPushNotificationRequestAttemptFail(pushNotificationConfig, pushNotificationRequestAttempt)
    }

    private void sendNotificationQueueInterrupted(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        customerAlertNotificationService.notifyPushNotificationQueueInterrupted(pushNotificationConfig.provider, pushNotificationConfig)

        pushNotificationMessageService.sendPushNotificationRequestInterrupted(pushNotificationConfig, pushNotificationRequestAttempt)

        pushNotificationConfigAlertQueueService.save(pushNotificationConfig, PushNotificationConfigAlertType.QUEUE_INTERRUPTED_7_DAYS, pushNotificationRequestAttempt)
    }

    private void sendNotificationInterruptedByFailureSevenDaysAgo(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        customerAlertNotificationService.notifyPushNotificationQueueInterruptedSevenDays(pushNotificationConfig.provider, pushNotificationConfig)

        pushNotificationMessageService.sendPushNotificationSevenDaysInterrupted(pushNotificationConfig, pushNotificationRequestAttempt)

        pushNotificationConfigAlertQueueService.save(pushNotificationConfig, PushNotificationConfigAlertType.QUEUE_INTERRUPTED_14_DAYS, pushNotificationRequestAttempt)
    }

    private void sendNotificationInterruptedByFailureFourteenDaysAgo(PushNotificationConfig pushNotificationConfig, PushNotificationRequestAttempt pushNotificationRequestAttempt) {
        customerAlertNotificationService.notifyPushNotificationQueueInterruptedFourteenDays(pushNotificationConfig.provider, pushNotificationConfig)

        pushNotificationMessageService.sendPushNotificationFourteenDaysInterrupted(pushNotificationConfig, pushNotificationRequestAttempt)
    }
}
