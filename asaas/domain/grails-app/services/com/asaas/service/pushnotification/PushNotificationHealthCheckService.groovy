package com.asaas.service.pushnotification

import com.asaas.domain.pushnotification.PushNotificationRequest
import com.asaas.domain.pushnotification.PushNotificationRequestAttempt
import com.asaas.domain.pushnotification.PushNotificationRequestPixEvent
import com.asaas.domain.pushnotification.PushNotificationType
import com.asaas.domain.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessing
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingStatus
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingType
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class PushNotificationHealthCheckService {

    def pushNotificationConfigWithPendingRequestCacheService

    public Boolean hasPendingRequestQueueDelay(PushNotificationType type) {
        if (type.isPix()) {
            return hasPixPendingRequestQueueDelay()
        }

        return true
    }

    public Boolean hasPixPendingRequestQueueDelay() {
        final Integer maxIntervalExponencialPenalty = 3
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, maxIntervalExponencialPenalty * -1)

        List<Long> pushNotificationRequestPixEventIdList = PushNotificationRequestPixEvent.createCriteria().list(max: 10) {
            projections {
                property "id"
            }

            createAlias("config", "config")
            eq("config.poolInterrupted", false)
            eq("config.enabled", true)
            eq("config.deleted", false)

            eq("sent", false)
            eq("deleted", false)

            sqlRestriction("coalesce(this_.next_execution_date, this_.date_created) < ?", [toleranceInstant])
        }

        if (!pushNotificationRequestPixEventIdList) {
            return false
        }

        AsaasLogger.info("PushNotificationHealthCheckService.hasPixPendingRequestQueueDelay -> Queue delay healthcheck failed. pushNotificationRequestPixEventIdList: ${pushNotificationRequestPixEventIdList} toleranceInstant: ${toleranceInstant}")
        return true
    }

    public Boolean hasPushNotificationRequestAsyncPreProcessingQueueDelay(PushNotificationRequestAsyncPreProcessingType type) {
        final Integer toleranceMinutes = 2
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, toleranceMinutes * -1)

        Map query = [
            columnList: ["dataJson", "customerId"],
            status: PushNotificationRequestAsyncPreProcessingStatus.PENDING,
            type: type,
            order: "asc",
            sort: "id",
            "dateCreated[lt]": toleranceInstant
        ]

        List<Map> asyncPreProcessingIdList = PushNotificationRequestAsyncPreProcessing.query(query).list(max: 50)
        if (!asyncPreProcessingIdList) return false

        AsaasLogger.info("PushNotificationHealthCheckService.hasPushNotificationRequestAsyncPreProcessingQueueDelay -> Queue delay healthcheck failed. asyncPreProcessingIdList: ${asyncPreProcessingIdList} toleranceInstant: ${toleranceInstant} | Total: ${asyncPreProcessingIdList.totalCount}")
        return true
    }

    private Map isPushNotificationRequestQueueProcessingNormally(List<PushNotificationRequestEvent> eventList) {
        List<Long> pushNotificationConfigIdList = pushNotificationConfigWithPendingRequestCacheService.list(eventList)

        Map result = [:]
        result.checkSuccess = true

        if (!pushNotificationConfigIdList) return result

        final Date executionDate = new Date()

        final Integer toleranceMinutes = 1
        Date toleranceInstant = CustomDateUtils.sumMinutes(executionDate, toleranceMinutes * -1)

        final Integer pushNotificationConfigLastUpdatedToleranceMinutes = 60
        Date pushNotificationConfigLastUpdatedToleranceInstant = CustomDateUtils.sumMinutes(executionDate, pushNotificationConfigLastUpdatedToleranceMinutes * -1)

        List<Long> pushNotificationConfigWithNotSentEventList = PushNotificationRequest.createCriteria().list() {
            createAlias('config', 'pushNotificationConfig')

            projections {
                property "config.id"
            }

            "in"("event", eventList)

            eq("deleted", false)
            eq("sent", false)
            eq("attemptsToSend", 0)
            le("lastUpdated", toleranceInstant)

            eq("pushNotificationConfig.deleted", false)
            eq("pushNotificationConfig.poolInterrupted", false)
            eq("pushNotificationConfig.enabled", true)
            le("pushNotificationConfig.lastUpdated", pushNotificationConfigLastUpdatedToleranceInstant)

            "in"("pushNotificationConfig.id", pushNotificationConfigIdList)
        }.unique()

        if (!pushNotificationConfigWithNotSentEventList) return result

        final Integer maxIntervalExponencialPenalty = 3
        Date attemptDateLimit = CustomDateUtils.sumMinutes(executionDate, maxIntervalExponencialPenalty * -1)

        Map attemptQuery = [
            column: "id",
            "pushNotificationConfigId[in]": pushNotificationConfigWithNotSentEventList,
            "event[in]": eventList,
            "dateCreated[ge]": attemptDateLimit,
            anyApplication: true
        ]

        Long pushNotificationRequestAttemptId = PushNotificationRequestAttempt.query(attemptQuery).get()
        if (!pushNotificationRequestAttemptId) {
            result.checkSuccess = false
            result.pushNotificationRequestConfigIdList = pushNotificationConfigWithNotSentEventList
            result.toleranceInstant = toleranceInstant
            result.pushNotificationConfigLastUpdatedToleranceInstant = pushNotificationConfigLastUpdatedToleranceInstant
            result.attemptDateLimit = attemptDateLimit
        }

        return result
    }

    public Boolean checkQueueDelay(List<PushNotificationRequestEvent> eventList) {
        Map queueProcessingNormallyResult = isPushNotificationRequestQueueProcessingNormally(eventList)

        if (!queueProcessingNormallyResult.checkSuccess) {
            AsaasLogger.info("PushNotificationHealthCheckService.checkQueueDelay -> Queue delay healthcheck failed. pushNotificationRequestConfigIdList: ${queueProcessingNormallyResult.pushNotificationRequestConfigIdList} toleranceInstant: ${queueProcessingNormallyResult.toleranceInstant}, pushNotificationConfigLastUpdatedToleranceInstant: ${queueProcessingNormallyResult.pushNotificationConfigLastUpdatedToleranceInstant}, attemptDateLimit: ${queueProcessingNormallyResult.attemptDateLimit}")
        }

        return queueProcessingNormallyResult.checkSuccess
    }
}
