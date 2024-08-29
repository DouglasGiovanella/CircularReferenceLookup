package com.asaas.service.payment

import com.asaas.domain.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessing
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingStatus
import com.asaas.pushnotificationrequestasyncpreprocessing.PushNotificationRequestAsyncPreProcessingType
import com.asaas.domain.payment.Payment
import com.asaas.domain.pushnotification.PushNotificationConfigEvent
import com.asaas.log.AsaasLogger
import com.asaas.namedqueries.SqlOrder
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.criterion.CriteriaSpecification

@Transactional
class PaymentPushNotificationRequestAsyncPreProcessingService {

    def pushNotificationRequestAsyncPreProcessingService
    def pushNotificationRequestPaymentEventService

    public List<Long> listCustomerIdToBeProcessed(List<Long> customerIdListProcessing, Integer availableThreads) {
        List<Map> pendingList = PushNotificationRequestAsyncPreProcessing.createCriteria().list(max: availableThreads) {
            projections {
                groupProperty("customerId")
                property("customerId", "customerId")
                min("id", "id")
            }

            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

            eq("deleted", false)
            eq("type", PushNotificationRequestAsyncPreProcessingType.PAYMENT)
            eq("status", PushNotificationRequestAsyncPreProcessingStatus.PENDING)

            if (customerIdListProcessing) {
                not { "in"("customerId", customerIdListProcessing) }
            }

            order(new SqlOrder("MIN(id) ASC"))
        }

        List<Long> customerIdList = pendingList.collect { it.customerId }

        return customerIdList
    }

    public void process(Integer maxItemsPerThread, Long customerId) {
        Map search = [column: "id", type: PushNotificationRequestAsyncPreProcessingType.PAYMENT, customerId: customerId]

        List<Long> pendingIdList = PushNotificationRequestAsyncPreProcessing.oldestPending(search).list(max: maxItemsPerThread)

        for (Long id : pendingIdList) {
            processPendingItem(id)
        }
    }

    public void save(Payment payment, PushNotificationRequestEvent event, Map additionalInfo = null) {
        Boolean hasPushNotificationConfig = PushNotificationConfigEvent.enabledConfig(payment.provider.id, event, [exists: true]).get().asBoolean()

        if (!hasPushNotificationConfig) return

        Map asyncPreProcessingData = [paymentId: payment.id, event: event]

        if (additionalInfo) {
            asyncPreProcessingData.additionalInfo = additionalInfo
        }

        pushNotificationRequestAsyncPreProcessingService.save(PushNotificationRequestAsyncPreProcessingType.PAYMENT, payment.id.toString(), payment.providerId, asyncPreProcessingData)
    }

    private void processPendingItem(Long id) {
        Utils.withNewTransactionAndRollbackOnError({
            PushNotificationRequestAsyncPreProcessing asyncPreProcessing = PushNotificationRequestAsyncPreProcessing.get(id)

            Map query = [:]
            query.groupId = asyncPreProcessing.groupId
            query.customerId = asyncPreProcessing.customerId
            query.type = PushNotificationRequestAsyncPreProcessingType.PAYMENT
            query.status = PushNotificationRequestAsyncPreProcessingStatus.CANCELLED
            query.exists = true

            Boolean hasAnyCancelledFromGroupId = PushNotificationRequestAsyncPreProcessing.query(query).get().asBoolean()
            if (hasAnyCancelledFromGroupId) {
                pushNotificationRequestAsyncPreProcessingService.cancel(asyncPreProcessing)

                return
            }

            Map asyncPreProcessingData = asyncPreProcessing.receiveDataAsMap()
            Payment payment = Payment.read(asyncPreProcessingData.paymentId)

            PushNotificationRequestEvent event = PushNotificationRequestEvent.valueOf(asyncPreProcessingData.event)

            pushNotificationRequestPaymentEventService.buildAndSave(payment, event, asyncPreProcessingData.additionalInfo)

            pushNotificationRequestAsyncPreProcessingService.delete(id)
        }, [ignoreStackTrace: true, onError: { Exception exception ->
            AsaasLogger.error("PaymentPushNotificationRequestAsyncPreProcessingService.process >> Falha ao processar id [${id}]", exception)

            Utils.withNewTransactionAndRollbackOnError({
                pushNotificationRequestAsyncPreProcessingService.sendToReprocessIfPossible(id)
            })
        }])
    }
}
