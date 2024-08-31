package com.asaas.service.notificationrequest

import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.notification.InstantTextMessage
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.sms.SmsMessage
import com.asaas.email.AsaasMailMessageStatus
import com.asaas.integration.instanttextmessage.enums.InstantTextMessageErrorReason
import com.asaas.notification.InstantTextMessageStatus
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationStatus
import com.asaas.notification.NotificationType
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class NotificationRequestHealthCheckService {

    public Boolean checkEmailQueueDelay() {
        final Integer toleranceSeconds = 30
        return checkQueueDelay(NotificationType.EMAIL, toleranceSeconds)
    }

    public Boolean checkSmsQueueDelay() {
        final Integer toleranceSeconds = 30
        return checkQueueDelay(NotificationType.SMS, toleranceSeconds)
    }

    public Boolean checkWhatsappQueueDelay() {
        final Integer toleranceSeconds = 30
        return checkQueueDelay(NotificationType.WHATSAPP, toleranceSeconds)
    }

    public Boolean checkSendingQueue(NotificationType notificationType) {
        final Integer maximumQueueItems = 5000

        Integer pendingCount
        if (notificationType.isEmail()) {
            pendingCount = AsaasMailMessage.query([status: AsaasMailMessageStatus.PENDING]).count()
        } else if (notificationType.isSms()) {
            pendingCount = SmsMessage.query([status: Status.PENDING]).count()
        } else if (notificationType.isWhatsApp()) {
            pendingCount = InstantTextMessage.query([status: InstantTextMessageStatus.PENDING]).count()
        } else {
            throw new NotImplementedException("O notificationType ${notificationType} informado não foi implementado!")
        }

        if (pendingCount > maximumQueueItems) return false

        return true
    }

    public Boolean checkNotificationRequestQueue(NotificationType notificationType) {
        try {
            final Date acceptableNotificationQueueEndTime = CustomDateUtils.sumMinutes(new Date(), 5)
            final Integer notificationRequestQueueLimit = 500

            Integer pendingNotificationCount = NotificationRequest.query([column: "id", status: NotificationStatus.PENDING, type: notificationType]).count()
            if (pendingNotificationCount < notificationRequestQueueLimit) return true

            Date notificationQueueCalculatedEndTime = calculateTimeToConsumeNotificationRequestQueue(notificationType, pendingNotificationCount)

            if (notificationQueueCalculatedEndTime < acceptableNotificationQueueEndTime) return true

            return checkNotificationRequestQueueHighTrafficPeriod(notificationType, notificationQueueCalculatedEndTime)
        } catch (Exception ignored) {
            return false
        }
    }

    public Boolean checkNotificationRequestStatusPage(NotificationType notificationType) {
        Integer toleranceInMinutes = 10
        Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), toleranceInMinutes * -1)

        Boolean isNotificationRequestWithDelay = NotificationRequest.executeQuery(
            "SELECT id FROM NotificationRequest WHERE type = :type AND status = :status AND lastUpdated < :tolerance AND priority = :priority",
            [status: NotificationStatus.PENDING, tolerance: toleranceDate, type: notificationType, priority: NotificationPriority.HIGH.priorityInt(), max: 1]
        )[0].asBoolean()

        if (isNotificationRequestWithDelay) return false
        return true
    }

    public Boolean checkAsaasMailMessageStatusPage() {
        Integer toleranceInMinutes = 10
        Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), toleranceInMinutes * -1)

        Boolean isAsaasMailMessageWithDelay = AsaasMailMessage.executeQuery(
            "SELECT id FROM AsaasMailMessage WHERE status = :status AND lastUpdated < :tolerance AND notificationRequest.priority = :priority",
            [status: AsaasMailMessageStatus.PENDING, tolerance: toleranceDate, priority: NotificationPriority.HIGH.priorityInt(), max: 1]
        )[0].asBoolean()

        if (isAsaasMailMessageWithDelay) return false
        return true
    }

    public Boolean checkSmsMessageStatusPage() {
        Integer toleranceInMinutes = 10
        Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), toleranceInMinutes * -1)

        Boolean isSmsMessageWithDelay = SmsMessage.nextOnQueue([
            column: "id",
            "lastUpdated[lt]": toleranceDate,
        ]).get() as Boolean

        if (isSmsMessageWithDelay) return false
        return true
    }

    public Boolean checkSendgridStatusPage() {
        Integer toleranceInMinutes = 5
        Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), toleranceInMinutes * -1)
        Long acceptableNumberOfErrors = 40

        Integer errorCount = AsaasMailMessage.query([
            "lastUpdated[ge]": toleranceDate,
            statusList: AsaasMailMessageStatus.getErrorOnSendStatusList()
        ]).count()

        if (errorCount <= acceptableNumberOfErrors) return true

        Integer totalCount = AsaasMailMessage.query([
            "lastUpdated[ge]": toleranceDate,
            statusList: AsaasMailMessageStatus.getErrorOnSendStatusList() + [AsaasMailMessageStatus.SENT]
        ]).count()

        Long errorPercentage = (Long) ((errorCount / totalCount) * 100)
        Boolean isErrorPercentageGreaterThenTwentyPercent = (errorPercentage > 20)

        if (isErrorPercentageGreaterThenTwentyPercent) return false
        return true
    }

    public Boolean checkInstantTextMessageStatusPage() {
        Integer toleranceInMinutes = 5
        Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), toleranceInMinutes * -1)
        Integer errorPercentageTolerance = 15

        Integer errorCount = InstantTextMessage.query([
            "lastUpdated[ge]": toleranceDate,
            statusList: InstantTextMessageStatus.getFailureSendingStatusList(),
            errorReasonList: InstantTextMessageErrorReason.getFailureSendingReasonList()
        ]).count()

        Integer totalCount = InstantTextMessage.query([
            "lastUpdated[ge]": toleranceDate,
            "statusList[notIn]": [InstantTextMessageStatus.CANCELLED, InstantTextMessageStatus.PENDING, InstantTextMessageStatus.SCHEDULED],
            "errorReasonList[orIsNull]": InstantTextMessageErrorReason.getFailureSendingReasonList()
        ]).count()

        BigDecimal errorPercentage = ((errorCount / totalCount) * 100)

        return errorPercentage <= errorPercentageTolerance
    }

    private Date calculateTimeToConsumeNotificationRequestQueue(NotificationType notificationType, Integer pendingNotificationCount) {
        Integer notificationSentPerMinute = calculateNotificationRequestSentPerMinute(notificationType, 10)

        Integer minutesToConsumeNotificationQueue = pendingNotificationCount / notificationSentPerMinute

        return CustomDateUtils.sumMinutes(new Date(), minutesToConsumeNotificationQueue)
    }

    private Integer calculateNotificationRequestSentPerMinute(NotificationType notificationType, Integer samplingMinutes) {
        Map queryParameters = [
            column: "id",
            type: notificationType,
            status: NotificationStatus.SENT,
            "lastUpdated[ge]": CustomDateUtils.sumMinutes(new Date(), samplingMinutes * -1)
        ]

        Integer notificationRequestCount = NotificationRequest.query(queryParameters).count()

        return notificationRequestCount / samplingMinutes
    }

    private Boolean checkNotificationRequestQueueHighTrafficPeriod(NotificationType notificationType, Date notificationQueueCalculatedEndTime) {
        final Date dateNow = new Date()
        final Date highTrafficStartingTime = getHighTrafficStartingTime(dateNow, notificationType)
        final Date highTrafficEstimatedEndTime = CustomDateUtils.setTime(dateNow, 11, 0, 0)
        final Date notificationSentPoolTime = CustomDateUtils.sumMinutes(highTrafficStartingTime, 10)

        if (dateNow < highTrafficStartingTime && dateNow > highTrafficEstimatedEndTime) return false

        if (dateNow >= highTrafficStartingTime && dateNow <= notificationSentPoolTime) return true

        if (highTrafficEstimatedEndTime < notificationQueueCalculatedEndTime) return true

        return false
    }

    private Date getHighTrafficStartingTime(NotificationType notificationType, Date date) {
        if (notificationType.isEmail()) return CustomDateUtils.setTime(date, 7, 15, 0)

        return CustomDateUtils.setTime(date, 8, 0, 0)
    }

    private Boolean checkQueueDelay(NotificationType type, Integer toleranceSeconds) {
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumSeconds(instant, -1 * toleranceSeconds)

        Boolean delayDetected = NotificationRequest.query(["dateCreated[lt]": toleranceInstant, "status": NotificationStatus.PENDING, "type": type, "priority[gt]": 0, exists: true]).get().asBoolean()

        if (delayDetected) return false
        return true
    }
}
