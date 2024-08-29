package com.asaas.service.notificationrequest

import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestViewingInfo
import com.asaas.exception.NotificationRequestViewingInfoException
import grails.transaction.Transactional
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.TransactionStatus

@Transactional
class NotificationRequestViewingInfoService {

    def notificationRequestService

    public Long saveNotificationViewedIfNecessary(NotificationRequest notificationRequest, Date viewedDate) {
        NotificationRequestViewingInfo existingViewingInfo = NotificationRequestViewingInfo.query([notificationRequest: notificationRequest, viewed: true]).get()
        if (existingViewingInfo) return existingViewingInfo.id

        Long viewingInfoId
        NotificationRequestViewingInfo.withNewTransaction { TransactionStatus status ->
            try {
                NotificationRequestViewingInfo viewingInfo = new NotificationRequestViewingInfo()
                viewingInfo.notificationRequest = notificationRequest
                viewingInfo.viewed = true
                viewingInfo.viewedDate = viewedDate
                viewingInfo.save(failOnError: true)

                if (!notificationRequest.viewed) notificationRequestService.setNotificationRequestAsViewed(notificationRequest.id, viewedDate)

                viewingInfoId = viewingInfo.id
            } catch (DataIntegrityViolationException dataIntegrityViolationException) {
                status.setRollbackOnly()
                return null
            } catch (CannotAcquireLockException cannotAcquireLockException) {
                status.setRollbackOnly()
                return null
            } catch (Exception exception) {
                status.setRollbackOnly()
                throw new NotificationRequestViewingInfoException("NotificationRequestViewingInfoService.saveNotificationViewedIfNecessary >> Erro ao registrar a vizualização da notificação ${notificationRequest.id} - ${exception.message}")
            }
        }

        return viewingInfoId
    }

    public void saveInvoiceViewedIfNecessary(Long paymentId, Long notificationRequestId) {
        NotificationRequest notificationRequest = NotificationRequest.query([id: notificationRequestId, paymentId: paymentId]).get()
        if (!notificationRequest) return

        NotificationRequestViewingInfo viewingInfo = NotificationRequestViewingInfo.query([notificationRequest: notificationRequest, disableSort: true]).get()

        if (!viewingInfo) {
            viewingInfo = new NotificationRequestViewingInfo()
            viewingInfo.viewed = true
            viewingInfo.viewedDate = new Date()
        }

        if (viewingInfo.invoiceViewed) return

        viewingInfo.notificationRequest = notificationRequest
        viewingInfo.invoiceViewed = true
        viewingInfo.invoiceViewedDate = new Date()
        viewingInfo.save(failOnError: true)
    }
}
