package com.asaas.service.notification

import com.asaas.domain.notification.AutomaticPaymentNotification
import com.asaas.domain.notification.Notification
import com.asaas.exception.BusinessException
import com.asaas.notification.AutomaticPaymentNotificationStatus
import grails.transaction.Transactional

@Transactional
class AutomaticPaymentNotificationService {

    public AutomaticPaymentNotification save(Long notificationId, Boolean validateDuplicatedOnDate) {
        Date notificationDate = new Date().clearTime()

        if (!validateDuplicatedOnDateIfNecessary(notificationDate, notificationId, validateDuplicatedOnDate)) {
            throw new BusinessException("Falha ao salvar processamento assíncrono de notificação de cobrança")
        }

        AutomaticPaymentNotification automaticPaymentNotification = new AutomaticPaymentNotification()
        automaticPaymentNotification.notification = Notification.load(notificationId)
        automaticPaymentNotification.notificationDate = notificationDate
        automaticPaymentNotification.status = AutomaticPaymentNotificationStatus.PENDING
        automaticPaymentNotification.save(failOnError: true)

        return automaticPaymentNotification
    }

    public void setAsErrorWithNewTransaction(Long id) {
        AutomaticPaymentNotification.withNewTransaction {
            AutomaticPaymentNotification automaticPaymentNotification = AutomaticPaymentNotification.get(id)
            automaticPaymentNotification.status = AutomaticPaymentNotificationStatus.ERROR
            automaticPaymentNotification.save(failOnError: true)
        }
    }

    public void deleteAll(List<Long> idList) {
        AutomaticPaymentNotification.executeUpdate("DELETE FROM AutomaticPaymentNotification apn WHERE apn.id IN (:idList)", [idList: idList])
    }

    private Boolean validateDuplicatedOnDateIfNecessary(Date notificationDate, Long notificationId, Boolean validateNotificationOnDate) {
        if (!validateNotificationOnDate) return true

        Boolean hasDuplicatedNotificationOnDate = AutomaticPaymentNotification.query([exists: true, notificationId: notificationId, notificationDate: notificationDate.clearTime()]).get().asBoolean()

        return !hasDuplicatedNotificationOnDate
    }
}
