package com.asaas.service.notification

import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationDispatcherPaymentNotificationMigrationOutbox
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.NotificationDispatcherPaymentNotificationOutboxEvent
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPaymentNotificationBaseDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishInstallmentUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishNotificationUpdatedBatchRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentUpdatedRequestDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishPaymentViewedRequestDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.ThreadUtils

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherPaymentNotificationMigrationOutboxService {

    def notificationDispatcherManagerService
    def notificationDispatcherCustomerAccountService

    public void saveCustomerAccountUpdated(CustomerAccount customerAccount) {
        NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishCustomerAccountUpdatedRequestDTO(customerAccount)
        save(customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.CUSTOMER_ACCOUNT_UPDATED, requestDTO)
    }

    public void saveNotificationUpdatedBatch(CustomerAccount customerAccount, List<Notification> notificationList) {
        NotificationDispatcherPublishNotificationUpdatedBatchRequestDTO requestDTO = new NotificationDispatcherPublishNotificationUpdatedBatchRequestDTO(notificationList)
        save(customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.NOTIFICATION_UPDATED_BATCH, requestDTO)
    }

    public void saveInstallmentUpdated(Installment installment) {
        NotificationDispatcherPublishInstallmentUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishInstallmentUpdatedRequestDTO(installment)
        save(installment.customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent.INSTALLMENT_UPDATED, requestDTO)
    }

    public void savePaymentUpdated(Payment payment) {
        NotificationDispatcherPublishPaymentUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentUpdatedRequestDTO(payment, false)
        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_UPDATED, requestDTO)
    }

    public void savePaymentDunning(PaymentDunning paymentDunning) {
        NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentDunningUpdatedRequestDTO(paymentDunning)
        save(paymentDunning.payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_DUNNING, requestDTO)
    }

    public void savePaymentViewed(Payment payment) {
        NotificationDispatcherPublishPaymentViewedRequestDTO requestDTO = new NotificationDispatcherPublishPaymentViewedRequestDTO(payment)
        save(payment.customerAccount.id, NotificationDispatcherPaymentNotificationOutboxEvent.PAYMENT_VIEWED, requestDTO)
    }

    public void saveCustomerAccountFullyIntegrated(Long customerAccountId) {
        save(customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent.CUSTOMER_ACCOUNT_FULLY_INTEGRATED, null)
    }

    public void processPendingOutboxEvents() {
        List<List<Map>> listOutboxDataGroupedByCustomerAccountId = listOutboxDataGroupedByCustomerAccountId()
        if (!listOutboxDataGroupedByCustomerAccountId) return

        final Integer minItemsForNewThread = 100

        List<Long> idToDeleteList = Collections.synchronizedList(new ArrayList<Long>())
        ThreadUtils.processWithThreadsOnDemand(listOutboxDataGroupedByCustomerAccountId, minItemsForNewThread, { List<List<Map>> outboxItemListPerThread ->
            List<Map> outboxItemList = outboxItemListPerThread.flatten()
            List<Long> successfulIdList = notificationDispatcherManagerService.sendPaymentNotificationOutboxMessages(outboxItemList)
            if (successfulIdList) {
                List<Map> successfulFullyIntegratedItemList = outboxItemList.findAll { it ->
                    successfulIdList.contains(it.id) && NotificationDispatcherPaymentNotificationOutboxEvent.valueOf(it.eventName.toString()).isCustomerAccountFullyIntegrated()
                }
                processCustomerAccountFullyIntegratedEvent(successfulFullyIntegratedItemList)
                idToDeleteList.addAll(successfulIdList)
            }
        })

        deleteBatch(idToDeleteList.sort())
    }

    private void processCustomerAccountFullyIntegratedEvent(List<Map> fullyIntegratedEventList) {
        if (!fullyIntegratedEventList) return
        for (Map customerAccountFullyIntegrated : fullyIntegratedEventList) {
            notificationDispatcherCustomerAccountService.confirmCustomerAccountFullyIntegrated(customerAccountFullyIntegrated.customerAccountId)
        }
    }

    private void save(Long customerAccountId, NotificationDispatcherPaymentNotificationOutboxEvent eventName, NotificationDispatcherPaymentNotificationBaseDTO payloadObject) {
        if (payloadObject) payloadObject.updateOnly = true

        NotificationDispatcherPaymentNotificationMigrationOutbox notificationDispatcherPaymentNotificationMigrationOutbox = new NotificationDispatcherPaymentNotificationMigrationOutbox()
        notificationDispatcherPaymentNotificationMigrationOutbox.customerAccountId = customerAccountId
        notificationDispatcherPaymentNotificationMigrationOutbox.eventName = eventName
        notificationDispatcherPaymentNotificationMigrationOutbox.payload = GsonBuilderUtils.toJsonWithoutNullFields(payloadObject)

        final Integer textLimitSize = 65535
        if (notificationDispatcherPaymentNotificationMigrationOutbox.payload.length() > textLimitSize) {
            AsaasLogger.error("NotificationDispatcherPaymentNotificationMigrationOutbox.save >> Payload do outbox de migração ultrapassou o limite de ${textLimitSize} caracteres: ${notificationDispatcherPaymentNotificationMigrationOutbox.payload}")
            return
        }

        notificationDispatcherPaymentNotificationMigrationOutbox.save(failOnError: true)
    }

    private List<List<Map>> listOutboxDataGroupedByCustomerAccountId() {
        final Integer maxPerExecution = 500

        List<Map> outboxPaymentNotificationMapList = NotificationDispatcherPaymentNotificationMigrationOutbox.query([
            columnList: ["id", "customerAccountId", "eventName", "payload"],
            sort: "id",
            order: "asc"
        ]).list(max: maxPerExecution)
        if (!outboxPaymentNotificationMapList) return null

        return outboxPaymentNotificationMapList.groupBy { it.customerAccountId }.values() as List<List<Map>>
    }

    private void deleteBatch(List<Long> idToDeleteList) {
        NotificationDispatcherPaymentNotificationMigrationOutbox.where {
            "in"("id", idToDeleteList)
        }.deleteAll()
    }
}
