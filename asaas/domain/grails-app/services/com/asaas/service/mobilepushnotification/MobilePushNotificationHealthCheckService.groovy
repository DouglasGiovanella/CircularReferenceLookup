package com.asaas.service.mobilepushnotification

import com.asaas.log.AsaasLogger
import com.asaas.mobilepushnotification.MobilePushNotificationPriority
import com.asaas.mobilepushnotification.MobilePushNotificationStatus
import com.asaas.mobilepushnotification.repository.MobilePushNotificationRepository
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class MobilePushNotificationHealthCheckService {

    private static final Integer MAX_ID_LIST_SIZE = 10
    private static final Integer LOW_PRIORITY_TOLERANCE_MINUTES = 30
    private static final Integer MEDIUM_PRIORITY_TOLERANCE_MINUTES = 15
    private static final Integer HIGH_PRIORITY_TOLERANCE_MINUTES = 5

    public Boolean checkLowPriorityQueueDelay() {
        return !hasPendingRequestWithDelay(MobilePushNotificationPriority.LOW, LOW_PRIORITY_TOLERANCE_MINUTES)
    }

    public Boolean checkMediumPriorityQueueDelay() {
        return !hasPendingRequestWithDelay(MobilePushNotificationPriority.MEDIUM, MEDIUM_PRIORITY_TOLERANCE_MINUTES)
    }

    public Boolean checkHighPriorityQueueDelay() {
        return !hasPendingRequestWithDelay(MobilePushNotificationPriority.HIGH, HIGH_PRIORITY_TOLERANCE_MINUTES)
    }

    private Boolean hasPendingRequestWithDelay(MobilePushNotificationPriority priority, Integer toleranceMinutes) {
        Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, toleranceMinutes * -1)

        Map query = [
            "dateCreated[lt]": toleranceInstant,
            status: MobilePushNotificationStatus.PENDING,
            priority: priority
        ]

        List<Long> mobilePushNotificationRequestIdList = MobilePushNotificationRepository.query(query)
            .disableRequiredFilters()
            .disableSort()
            .column("id")
            .list(max: MAX_ID_LIST_SIZE)

        Boolean hasDelay = mobilePushNotificationRequestIdList.size() > 0

        if (hasDelay) {
            AsaasLogger.warn("MobilePushNotificationHealthCheck >> Comportamento inesperado na fila de notificações push no app. Priority: ${priority}, idList: ${mobilePushNotificationRequestIdList}")
        }

        return hasDelay
    }
}
