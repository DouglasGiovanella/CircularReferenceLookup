package com.asaas.service.paymentnotificationhistory

import com.asaas.domain.payment.Payment
import com.asaas.domain.timeline.TimelineEvent
import com.asaas.integration.notificationdispatcher.adapter.NotificationDispatcherPaymentNotificationHistoryAdapter
import com.asaas.integration.notificationdispatcher.adapter.PagedNotificationDispatcherPaymentNotificationHistoryAdapter
import com.asaas.integration.notificationdispatcher.dto.NotificationDispatcherPaymentNotificationHistoryRequestDTO
import com.asaas.notificationhistory.NotificationHistoryVO
import com.asaas.pagination.SequencedResultList
import com.asaas.timeline.TimelineEventType

import grails.transaction.Transactional

@Transactional
class PaymentNotificationHistoryService {

    def featureFlagService
    def notificationDispatcherManagerService
    def notificationHistoryDescriptionService

    public Map list(Map params, Integer offset, Integer lastNotificationDispatcherOffset, Integer limit) {
        Boolean hasPreviousPage = offset > 0
        Boolean listFromNotificationDispatcher = false

        if (lastNotificationDispatcherOffset != null) {
            listFromNotificationDispatcher = (offset - lastNotificationDispatcherOffset) <= 0
        } else {
            lastNotificationDispatcherOffset = 0
        }

        List<NotificationHistoryVO> notificationHistoryVOList = []
        if (listFromNotificationDispatcher && featureFlagService.isNotificationRequestExternalProcessingEnabled()) {
            NotificationDispatcherPaymentNotificationHistoryRequestDTO notificationHistoryRequestDTO = new NotificationDispatcherPaymentNotificationHistoryRequestDTO(params.paymentId, params.customerAccountId, limit, offset)
            PagedNotificationDispatcherPaymentNotificationHistoryAdapter pagedPaymentNotificationHistoryResponse = notificationDispatcherManagerService.listPaymentNotificationHistory(notificationHistoryRequestDTO)
            pagedPaymentNotificationHistoryResponse.data?.forEach { it ->
                String notificationDescription = buildNotificationDescription(it)
                notificationHistoryVOList.add(NotificationHistoryVO.buildFromNotificationDispatcherPaymentNotificationHistoryAdapter(it, notificationDescription))
            }
            lastNotificationDispatcherOffset = offset + notificationHistoryVOList.size()
            if (pagedPaymentNotificationHistoryResponse.hasMore) {
                return [
                    notificationDispatcherOffset: lastNotificationDispatcherOffset,
                    sequencedResult: new SequencedResultList(notificationHistoryVOList, limit, offset, hasPreviousPage, true)]
            }
        }

        Map timelineEventMapSearch = params.clone()
        timelineEventMapSearch.typeList = TimelineEventType.listNotificationHistoryEventTypeFilter()

        Integer databaseOffset = Math.max(0, offset - lastNotificationDispatcherOffset)
        Integer maxQuerySize = limit - notificationHistoryVOList.size() + 1
        TimelineEvent.query(timelineEventMapSearch).list(max: maxQuerySize, offset: databaseOffset, readOnly: true).forEach { it -> notificationHistoryVOList.add(NotificationHistoryVO.buildFromTimeLineEvent(it)) }

        Boolean hasNextPage = notificationHistoryVOList.size() > limit
        if (hasNextPage) notificationHistoryVOList.removeAt(limit)

        return [
            notificationDispatcherOffset: lastNotificationDispatcherOffset,
            sequencedResult: new SequencedResultList(notificationHistoryVOList, limit, offset, hasPreviousPage, hasNextPage)
        ]
    }

    private String buildNotificationDescription(NotificationDispatcherPaymentNotificationHistoryAdapter notificationHistoryAdapter) {
        boolean needPaymentToBuildDescription = (notificationHistoryAdapter.notificationType.isPhoneCall() && notificationHistoryAdapter.phoneCallStatus) || notificationHistoryAdapter.notificationEvent.isPaymentRefundRequested() || notificationHistoryAdapter.notificationEvent.isPaymentRefundRequestExpired()
        if (!needPaymentToBuildDescription) return notificationHistoryDescriptionService.buildNotificationHistoryDescription(notificationHistoryAdapter.notificationStatus, notificationHistoryAdapter.notificationType, notificationHistoryAdapter.notificationEvent, notificationHistoryAdapter.notificationReceiver, notificationHistoryAdapter.destination, null)

        Payment payment = Payment.get(notificationHistoryAdapter.id)
        if (notificationHistoryAdapter.notificationType.isPhoneCall() && notificationHistoryAdapter.phoneCallStatus) return notificationHistoryDescriptionService.getPhoneCallInteractionNotificationDescription(notificationHistoryAdapter.destination, payment.getInvoiceNumber())
        if (notificationHistoryAdapter.notificationEvent.isPaymentRefundRequested()) return notificationHistoryDescriptionService.getRefundRequestCreatedNotificationDescription(payment)
        if (notificationHistoryAdapter.notificationEvent.isPaymentRefundRequestExpired()) return notificationHistoryDescriptionService.getRefundRequestExpiredNotificationDescription(payment)
    }
}
