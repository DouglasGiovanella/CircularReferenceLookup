package com.asaas.service.receivablehub.outbox

import com.asaas.domain.payment.Payment
import com.asaas.domain.receivablehub.ReceivableHubPaymentOutbox
import com.asaas.receivablehub.dto.ReceivableHubPublishPaymentChargebackReversedRequestDTO
import com.asaas.receivablehub.dto.ReceivableHubPublishPaymentConfirmedRequestDTO
import com.asaas.receivablehub.dto.ReceivableHubPublishPaymentDTO
import com.asaas.receivablehub.dto.ReceivableHubPublishPaymentPartiallyRefundedRequestDTO
import com.asaas.receivablehub.dto.ReceivableHubPublishPaymentUpdatedRequestDTO
import com.asaas.receivablehub.enums.ReceivableHubPaymentOutboxEventName
import com.asaas.receivablehub.repository.ReceivableHubPaymentOutboxRepository
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.service.integration.cerc.PaymentArrangementService
import com.asaas.service.receivablehub.ReceivableHubManagerService
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.ThreadUtils
import com.asaas.service.featureflag.FeatureFlagService
import com.asaas.utils.Utils
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import groovy.transform.CompileDynamic

@GrailsCompileStatic
@Transactional
class ReceivableHubPaymentOutboxService {

    FeatureFlagService featureFlagService
    PaymentArrangementService paymentArrangementService
    ReceivableHubManagerService receivableHubManagerService

    public void processPendingOutboxEvents() {
        final Integer maxPaymentsPerExecution = 500
        final Integer minPaymentsForNewThread = 100
        final Integer maxItemsPerThread = 100

        List<Long> outboxPaymentIdList = ReceivableHubPaymentOutboxRepository.query([:]).disableSort().distinct("paymentId").list(max: maxPaymentsPerExecution) as List<Long>
        if (!outboxPaymentIdList) return

        List<Long> outboxPaymentIdToDeleteList = Collections.synchronizedList(new ArrayList<Long>())

        ThreadUtils.processWithThreadsOnDemand(outboxPaymentIdList, minPaymentsForNewThread, { List<Long> paymentIdSubList ->
            List<Map> outboxItemList = []

            Utils.withNewTransactionAndRollbackOnError({
                List<ReceivableHubPaymentOutbox> outboxList = ReceivableHubPaymentOutboxRepository.query(["paymentId[in]": paymentIdSubList])
                    .sort("id", "asc")
                    .list(max: maxItemsPerThread) as List<ReceivableHubPaymentOutbox>

                for (ReceivableHubPaymentOutbox outbox : outboxList) {
                    String payload = buildPayloadObject(outbox)
                    outboxItemList.add([
                        "id": outbox.id,
                        "paymentId": outbox.paymentId,
                        "eventName": outbox.eventName,
                        "payload": payload
                    ])
                }
            }, [logErrorMessage: "ReceivableHubPaymentOutboxService.processPendingOutboxEvents >> Erro ao buildar informações para as cobranças com ids ${paymentIdSubList}"])

            List<Long> successfulIdList = receivableHubManagerService.sendPaymentOutboxMessages(outboxItemList)
            if (successfulIdList) outboxPaymentIdToDeleteList.addAll(successfulIdList)
        })

        deleteWithNewTransaction(outboxPaymentIdToDeleteList)
    }

    public void savePaymentConfirmed(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_CONFIRMED)
    }

    public void savePaymentRefunded(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_REFUNDED)
    }

    public void savePaymentPartiallyRefunded(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_PARTIALLY_REFUNDED)
    }

    public void savePaymentChargebackRequested(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_CHARGEBACK_REQUESTED)
    }

    public void savePaymentChargebackReversed(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_CHARGEBACK_REVERSED)
    }

    public void saveConfirmedPaymentUpdated(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return
        if (!payment.status.isConfirmed()) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_UPDATED)
    }

    public void savePaymentReceived(Payment payment) {
        if (!shouldSaveOutboxEvent(payment)) return

        save(payment.id, ReceivableHubPaymentOutboxEventName.PAYMENT_RECEIVED)
    }

    private void save(Long paymentId, ReceivableHubPaymentOutboxEventName eventName) {
        ReceivableHubPaymentOutbox receivableHubOutbox = new ReceivableHubPaymentOutbox()
        receivableHubOutbox.paymentId = paymentId
        receivableHubOutbox.eventName = eventName
        receivableHubOutbox.save(failOnError: true)
    }

    private Boolean shouldSaveOutboxEvent(Payment payment) {
        if (!payment.billingType.isCreditCardOrDebitCard()) return false
        if (!featureFlagService.isReceivableHubOutboxEnabled()) return false

        return true
    }

    private String buildPayloadObject(ReceivableHubPaymentOutbox outbox) {
        ReceivableHubPublishPaymentDTO payloadObject = null

        Payment payment = Payment.read(outbox.paymentId)

        switch (outbox.eventName) {
            case ReceivableHubPaymentOutboxEventName.PAYMENT_CONFIRMED:
                PaymentArrangement arrangement = paymentArrangementService.getPaymentArrangement(payment, false)
                payloadObject = new ReceivableHubPublishPaymentConfirmedRequestDTO(payment, arrangement)
                break
            case ReceivableHubPaymentOutboxEventName.PAYMENT_CHARGEBACK_REVERSED:
                payloadObject = new ReceivableHubPublishPaymentChargebackReversedRequestDTO(payment)
                break
            case ReceivableHubPaymentOutboxEventName.PAYMENT_REFUNDED:
            case ReceivableHubPaymentOutboxEventName.PAYMENT_CHARGEBACK_REQUESTED:
            case ReceivableHubPaymentOutboxEventName.PAYMENT_RECEIVED:
                payloadObject = new ReceivableHubPublishPaymentDTO(payment)
                break
            case ReceivableHubPaymentOutboxEventName.PAYMENT_PARTIALLY_REFUNDED:
                payloadObject = new ReceivableHubPublishPaymentPartiallyRefundedRequestDTO(payment)
                break
            case ReceivableHubPaymentOutboxEventName.PAYMENT_UPDATED:
                payloadObject = new ReceivableHubPublishPaymentUpdatedRequestDTO(payment)
                break
            default:
                throw new UnsupportedOperationException("Não é possivel buildar o payload do evento ${outbox.eventName}")
                break
        }

        return GsonBuilderUtils.toJsonWithoutNullFields(payloadObject)
    }

    @CompileDynamic
    private void deleteWithNewTransaction(List<Long> idToDeleteList) {
        final Integer maxItemsPerOperation = 500

        Utils.withNewTransactionAndRollbackOnError({
            for (List<Long> collatedList : idToDeleteList.collate(maxItemsPerOperation)) {
                ReceivableHubPaymentOutbox.where {
                    "in"("id", collatedList)
                }.deleteAll()
            }
        }, [logErrorMessage: "ReceivableHubPaymentOutboxService.deleteWithNewTransaction >> Erro ao deletar outbox com ids ${idToDeleteList}"])
    }
}
