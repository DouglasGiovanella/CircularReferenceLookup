package com.asaas.service.notificationrequest

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.notification.NotificationRequestUpdateSendingStatusAsyncAction
import com.asaas.domain.timeline.TimelineEvent
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationStatus
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusBatchVO
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.notificationtemplate.NotificationRequestProcessUpdateStatusManagerVO
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class NotificationRequestUpdateSendingStatusAsyncActionService {

    def baseAsyncActionService
    def dataSource
    def grailsApplication
    def notificationRequestStatusService
    def timelineEventService

    public void save(Long notificationRequestId, Long notificationRequestToId, NotificationStatus status, String failReason, Boolean createTimelineEvent) {
        Map asyncActionData = [
            notificationRequestId: notificationRequestId,
            notificationRequestToId: notificationRequestToId,
            status: status,
            failReason: failReason,
            createTimelineEvent: createTimelineEvent
        ]

        NotificationRequestUpdateSendingStatusAsyncAction asyncAction = new NotificationRequestUpdateSendingStatusAsyncAction()
        asyncAction.notificationRequestId = notificationRequestId

        baseAsyncActionService.save(asyncAction, asyncActionData)
    }

    public void saveInBatch(List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList) {
        final String queuesSchemaName = grailsApplication.config.asaas.database.schema.queues.name

        List<Map> dataToInsert = updateSendingStatusVOList.collect { NotificationRequestUpdateSendingStatusVO updateSendingStatusVO ->
            Map actionData = updateSendingStatusVO.toMap()
            String jsonActionData = AsyncActionDataBuilder.parseToJsonString(actionData)

            return [
                "action_data": jsonActionData,
                "action_data_hash": AsyncActionDataBuilder.buildHash(jsonActionData),
                "date_created": new Date(),
                "deleted": 0,
                "last_updated": new Date(),
                "notification_request_id": updateSendingStatusVO.notificationRequestId,
                "status": AsyncActionStatus.PENDING.toString(),
                "version": "0"
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "${queuesSchemaName}.notification_request_update_sending_status_async_action", dataToInsert)
    }

    public void processUpdateSendingStatus() {
        final Integer maxNumberOfGroupIdPerExecution = 400
        final Integer maxItemsPerThread = 50
        final Integer maxAsyncActionsPerThread = 200

        List<Long> groupIdList = NotificationRequestUpdateSendingStatusAsyncAction.query([
            distinct: "notificationRequestId",
            status: AsyncActionStatus.PENDING,
            disableSort: true,
            includeDeleted: true
        ]).list(max: maxNumberOfGroupIdPerExecution)

        if (!groupIdList) return

        List<Long> asyncActionDeleteIdList = Collections.synchronizedList([])
        List<Long> asyncActionErrorIdList = Collections.synchronizedList([])

        ThreadUtils.processWithThreadsOnDemand(groupIdList, maxItemsPerThread, { List<Long> subGroupIdList ->
            try {
                Utils.withNewTransactionAndRollbackOnError ({
                    Map subGroupSearch = ["notificationRequestId[in]": subGroupIdList]
                    List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(NotificationRequestUpdateSendingStatusAsyncAction, subGroupSearch, maxAsyncActionsPerThread)
                    NotificationRequestProcessUpdateStatusManagerVO updateRequestManagerVO = new NotificationRequestProcessUpdateStatusManagerVO(asyncActionDataList)
                    asyncActionDeleteIdList.addAll(updateRequestManagerVO.getUpdateRequestInvalidStatusAsyncActionIdList())

                    Queue<TimelineEvent> timelineEventQueue = buildTimelineEventList(updateRequestManagerVO)
                    updateNotificationRequestStatusInBatch(updateRequestManagerVO, timelineEventQueue, asyncActionErrorIdList)
                    createTimelineEventInBatch(updateRequestManagerVO, timelineEventQueue)

                    asyncActionDeleteIdList.addAll(updateRequestManagerVO.getProcessedAsyncActionIdList())
                }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
            } catch (Exception exception) {
                AsaasLogger.error("NotificationRequestUpdateSendingStatusAsyncActionService.processUpdateSendingStatus >> Erro ao processar atualização de status para NotificationRequests: ${ subGroupIdList }", exception)
            }
        })

        Utils.withNewTransactionAndRollbackOnError {
            baseAsyncActionService.updateStatus(NotificationRequestUpdateSendingStatusAsyncAction, asyncActionErrorIdList, AsyncActionStatus.ERROR)
            baseAsyncActionService.deleteList(NotificationRequestUpdateSendingStatusAsyncAction, asyncActionDeleteIdList)
        }
    }

    private Queue<TimelineEvent> buildTimelineEventList(NotificationRequestProcessUpdateStatusManagerVO updateRequestManagerVO) {
        List<Long> createTimelineEventIdList = updateRequestManagerVO.getNotificationRequestToCreateTimelineEventIdList()
        if (!createTimelineEventIdList) return new LinkedList<TimelineEvent>()

        Queue<TimelineEvent> timelineEventQueue = new LinkedList<TimelineEvent>()

        List<NotificationRequest> notificationRequestList = NotificationRequest.query([
            "id[in]": createTimelineEventIdList,
            disableSort: true
        ]).list(readOnly: true)

        for (NotificationRequest notificationRequest : notificationRequestList) {
            NotificationRequestUpdateSendingStatusBatchVO updateRequestBatchVO = updateRequestManagerVO.getUpdateRequestBatch(notificationRequest.id)

            for (NotificationRequestUpdateSendingStatusVO updateRequestVO : updateRequestBatchVO.getCreateTimelineEventUpdateRequestList()) {
                String failReason = updateRequestVO.failReason
                NotificationStatus notificationStatus = updateRequestVO.status

                NotificationRequestTo notificationRequestTo
                if (updateRequestVO.notificationRequestToId) {
                    notificationRequestTo = NotificationRequestTo.read(updateRequestVO.notificationRequestToId)
                }

                TimelineEvent timelineEvent = timelineEventService.buildNotificationEventObject(notificationStatus, notificationRequest, notificationRequestTo, failReason)
                timelineEvent.discard()
                timelineEventQueue.add(timelineEvent)
            }
        }

        return timelineEventQueue
    }

    private void updateNotificationRequestStatusInBatch(NotificationRequestProcessUpdateStatusManagerVO updateRequestManagerVO, Queue<TimelineEvent> timelineEventQueue, List<Long> asyncActionErrorIdList) {
        updateRequestManagerVO.buildNotificationRequestToUpdateList()
        for (NotificationStatus updateStatus : [NotificationStatus.SENT, NotificationStatus.FAILED]) {
            List<Long> notificationRequestIdList = updateRequestManagerVO.getNotificationRequestToUpdateIdList(updateStatus)
            if (!notificationRequestIdList) continue

            try {
                notificationRequestStatusService.updateSendingStatusInBatch(notificationRequestIdList, updateStatus)
            } catch (Exception exception) {
                timelineEventQueue.removeAll { notificationRequestIdList.contains(it.notificationRequest.id) }
                updateRequestManagerVO.removeAllUpdateRequestBatchByIdList(notificationRequestIdList)

                if (Utils.isLock(exception)) continue

                List<Long> asyncActionIdList = updateRequestManagerVO.getProcessingUpdateAsyncActionIdList(updateStatus)
                asyncActionErrorIdList.addAll(asyncActionIdList)

                AsaasLogger.error("NotificationRequestUpdateSendingStatusAsyncActionService.updateNotificationRequestStatusInBatch >> Erro ao efetuar atualização para status [${ updateStatus }] das NotificationRequets com IDs: ${ notificationRequestIdList } via AsyncActions com IDs: ${ asyncActionIdList }", exception)
            }
        }
    }

    private void createTimelineEventInBatch(NotificationRequestProcessUpdateStatusManagerVO updateRequestManagerVO, Queue<TimelineEvent> timelineEventQueue) {
        if (!timelineEventQueue) return

        try {
            timelineEventService.saveInBatch(timelineEventQueue as List<TimelineEvent>)
        } catch (Exception exception) {
            List<Long> notificationRequestIdList = timelineEventQueue*.notificationRequestId
            AsaasLogger.error("NotificationRequestUpdateSendingStatusAsyncActionService.createTimelineEventInBatch >> Erro ao criar TimelineEvents em lote para NotificationRequests: ${ notificationRequestIdList }", exception)

            updateRequestManagerVO.removeAllUpdateRequestBatchByIdList(notificationRequestIdList)
        }
    }
}
