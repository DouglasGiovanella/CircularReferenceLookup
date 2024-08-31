package com.asaas.service.mobilepushnotification

import com.asaas.domain.mobilepushnotification.MobilePushNotification
import com.asaas.mobilepushnotification.repository.MobilePushNotificationRepository
import com.asaas.log.AsaasLogger
import com.asaas.mobilepushnotification.MobilePushNotificationSender
import com.asaas.mobilepushnotification.MobilePushNotificationStatus
import com.asaas.mobilepushnotification.worker.MobilePushNotificationWorkerConfigVO
import com.asaas.mobilepushnotification.worker.MobilePushNotificationWorkerItemVO

import grails.transaction.Transactional

@Transactional
class MobilePushNotificationSenderService {

    def userMobileDeviceService

    public List<MobilePushNotificationWorkerItemVO> listPendingMobilePushNotification(MobilePushNotificationWorkerConfigVO mobilePushNotificationWorkerConfig, List<Long> mobilePushNotificationPendingIdListProcessing, Integer maxQueryItems) {
        Map search = [status: MobilePushNotificationStatus.PENDING, priority: mobilePushNotificationWorkerConfig.priority]

        if (mobilePushNotificationPendingIdListProcessing) {
            search."id[notIn]" = mobilePushNotificationPendingIdListProcessing
        }

        List<Long> mobilePushNotificationList = MobilePushNotificationRepository.query(search)
            .disableRequiredFilters()
            .column("id")
            .sort("id", "asc")
            .list(max: maxQueryItems)

        List<MobilePushNotificationWorkerItemVO> itemList = []
        mobilePushNotificationList.collate(mobilePushNotificationWorkerConfig.maxItemsPerThread).each { itemList.add(new MobilePushNotificationWorkerItemVO(it)) }

        return itemList
    }

    public processPendingNotifications(List<Long> mobilePushNotificationList) {
        if (!mobilePushNotificationList) return
        updateToProcessing(mobilePushNotificationList)

        sendList(mobilePushNotificationList)
    }

    private void updateToProcessing(List<Long> mobilePushNotificationList) {
        MobilePushNotification.withNewTransaction { status ->
            MobilePushNotification.executeUpdate("UPDATE MobilePushNotification SET version = version + 1, status = :status, lastUpdated = :lastUpdated WHERE id IN (:ids)", [status: MobilePushNotificationStatus.PROCESSING, lastUpdated: new Date(), ids: mobilePushNotificationList])
        }
    }

    private void sendList(List<Long> mobilePushNotificationList) {
        for (Long id in mobilePushNotificationList) {
            MobilePushNotification.withNewTransaction { status ->
                MobilePushNotification mobilePushNotification = MobilePushNotification.get(id)

                try {
                    mobilePushNotification.attempts = mobilePushNotification.attempts + 1

                    MobilePushNotificationSender notificationSender = new MobilePushNotificationSender(mobilePushNotification, mobilePushNotification.title, mobilePushNotification.body, mobilePushNotification.dataJson)
                    notificationSender.execute()

                    if (notificationSender.invalidToken) {
                        userMobileDeviceService.delete(notificationSender.token)
                        mobilePushNotification.status = MobilePushNotificationStatus.FAILED
                    } else if (notificationSender.unavailable) {
                        handleFailedAttempt(mobilePushNotification)
                    } else if (!notificationSender.success) {
                        mobilePushNotification.status = MobilePushNotificationStatus.FAILED
                    } else {
                        mobilePushNotification.status = MobilePushNotificationStatus.PROCESSED
                    }
                } catch (Exception e) {
                    handleFailedAttempt(mobilePushNotification)
                    AsaasLogger.error("MobilePushNotificationSenderService.sendList >>> Error ao processar [${id}]", e)
                } finally {
                    mobilePushNotification.save(failOnError: true)
                }
            }
        }
    }

    private void handleFailedAttempt(MobilePushNotification mobilePushNotification) {
        if (mobilePushNotification.attempts < MobilePushNotification.MAX_ATTEMPTS) {
            mobilePushNotification.status = MobilePushNotificationStatus.PENDING
        } else {
            mobilePushNotification.status = MobilePushNotificationStatus.FAILED
            AsaasLogger.warn("MobilePushNotificationSenderService.handleFailedAttempt -> Esgotou as tentativas de envio pushId:[${mobilePushNotification.id}]]")
        }
    }
}
