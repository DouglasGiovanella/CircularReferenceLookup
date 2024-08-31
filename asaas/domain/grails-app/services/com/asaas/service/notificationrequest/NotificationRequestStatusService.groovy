package com.asaas.service.notificationrequest

import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationStatus
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class NotificationRequestStatusService {

    def notificationRequestUpdateSendingStatusAsyncActionService
    def sessionFactory
    def timelineEventService

    public void setAsFailed(Long notificationRequestId, String failReason, Boolean createTimelineEvent) {
        notificationRequestUpdateSendingStatusAsyncActionService.save(notificationRequestId, null, NotificationStatus.FAILED, failReason, createTimelineEvent)
    }

    public void setAsSent(Long notificationRequestId, Long notificationRequestToId, Boolean createTimelineEvent) {
        notificationRequestUpdateSendingStatusAsyncActionService.save(notificationRequestId, notificationRequestToId, NotificationStatus.SENT, null, createTimelineEvent)
    }

    public NotificationRequestUpdateSendingStatusVO buildFailedObject(Long notificationRequestId, String failReason, Boolean createTimelineEvent) {
        return new NotificationRequestUpdateSendingStatusVO(notificationRequestId, null, NotificationStatus.FAILED, failReason, createTimelineEvent)
    }

    public NotificationRequestUpdateSendingStatusVO buildSentObject(Long notificationRequestId, Long notificationRequestToId, Boolean createTimelineEvent) {
        return new NotificationRequestUpdateSendingStatusVO(notificationRequestId, notificationRequestToId, NotificationStatus.SENT, null, createTimelineEvent)
    }

    public void saveAsyncUpdateSendingStatusList(List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList) {
        notificationRequestUpdateSendingStatusAsyncActionService.saveInBatch(updateSendingStatusVOList)
    }

    public void updateSendingStatus(NotificationRequest notificationRequest, NotificationStatus notificationStatus, NotificationRequestTo notificationRequestTo, String failReason, Boolean createTimelineEvent) {
        if (!notificationStatus.isProcessed()) return

        if (notificationRequest.status != notificationStatus) {
            notificationRequest.status = notificationStatus
            notificationRequest.save(failOnError: true)
        }

        if (createTimelineEvent) timelineEventService.createNotificationEvent(notificationStatus, notificationRequest, notificationRequestTo, failReason, true)
    }

    public void updateSendingStatusInBatch(List<Long> notificationRequestIdList, NotificationStatus newStatus) {
        final Integer maxBatchSize = 1000

        try {
            Utils.withNewTransactionAndRollbackOnError ({
                for (List<Long> batchIdList : notificationRequestIdList.collate(maxBatchSize)) {
                    StringBuilder sqlStatement = new StringBuilder()
                    sqlStatement.append(" UPDATE notification_request nr ")
                    sqlStatement.append(" FORCE INDEX (primary) ")
                    sqlStatement.append(" SET nr.status = :newStatus, ")
                    sqlStatement.append("     nr.version = nr.version + 1, ")
                    sqlStatement.append("     nr.last_updated = :lastUpdated ")
                    sqlStatement.append(" WHERE nr.id IN (:idList) ")

                    SQLQuery query = sessionFactory.currentSession.createSQLQuery(sqlStatement.toString())
                    query.setString("newStatus", newStatus.toString())
                    query.setString("lastUpdated", CustomDateUtils.fromDate(new Date(), CustomDateUtils.DATABASE_DATETIME_FORMAT))
                    query.setParameterList("idList", batchIdList)
                    query.executeUpdate()
                }
            }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
        } catch (Exception exception) {
            AsaasLogger.error("NotificationRequestStatusService.updateSendingStatusInBatch >> Erro ao atualizar status para [${ newStatus }] das NotificationRequests com IDs: ${ notificationRequestIdList }", exception)
            throw exception
        }
    }
}
