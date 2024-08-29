package com.asaas.service.receivableunit

import com.asaas.asyncaction.AsyncActionType
import com.asaas.chargeback.ChargebackStatus
import com.asaas.domain.chargeback.ChargebackScheduledSettlement
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PrepareReceivableUnitItemService {

    private static final Integer MAX_ITEMS_PER_CYCLE = 250

    def asyncActionService
    def cercCompanyService
    def receivableRegistrationEventQueueService
    def receivableUnitItemService

    public void processSaveReceivableUnitItem() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_RECEIVABLE_UNIT_ITEM, MAX_ITEMS_PER_CYCLE)

        for (Map asyncActionData : asyncActionDataList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.read(asyncActionData.paymentId)
                if (payment.status.isConfirmed()) receivableUnitItemService.save(payment)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "PrepareReceivableUnitItemService.processSaveReceivableUnitItem >> Falha ao salvar ReceivableUnitItem para a cobrança [${asyncActionData.paymentId}] AsyncAction [${asyncActionData.asyncActionId}]",
            onError: { hasError = true }])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }

    public void processReceivableUnitItemRefunded() {
        final Integer maxItemsPerExecution = 500
        List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.RECEIVABLE_UNIT_ITEM_REFUNDED, null, null, maxItemsPerExecution)

        for (Map eventData : eventDataList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem item = ReceivableUnitItem.notSettled(["contractualEffect[isNull]": true, "anticipatedReceivableUnit[isNull]": true, paymentId: eventData.paymentId]).get()

                if (item) receivableUnitItemService.processRefund(item)

                receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
            }, [logErrorMessage: "PrepareReceivableUnitItemService.processReceivableUnitItemRefunded --> Falha ao processar evento [${eventData.eventQueueId}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                }, [logErrorMessage: "PrepareReceivableUnitItemService.processReceivableUnitItemRefunded --> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
            }
        }
    }

    public void processPendingItems() {
        Map search = [:]
        search.column = "id"
        search.status = ReceivableUnitItemStatus.PENDING
        search.includeDeleted = true
        search.sort = "id"
        search.order = "asc"

        List<Long> itemIdList = ReceivableUnitItem.query(search).list(max: MAX_ITEMS_PER_CYCLE)
        for (Long itemId in itemIdList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem item = ReceivableUnitItem.get(itemId)
                cercCompanyService.saveIfNecessary(item.customerCpfCnpj)
                receivableUnitItemService.process(item)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("PrepareReceivableUnitItemService.processPendingItems >> Ocorreu um lock ao processar item da UR [ReceivableUnitItemId: ${itemId}]" , exception)
                        return
                    }

                    AsaasLogger.error("PrepareReceivableUnitItemService.processPendingItems >> Erro ao processar item da UR [ReceivableUnitItemId: ${itemId}]" , exception)
                    hasError = true
                }
            ])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableUnitItem item = ReceivableUnitItem.get(itemId)
                    receivableUnitItemService.setAsError(item)
                }, [logErrorMessage: "PrepareReceivableUnitItemService.processPendingItems >> Erro ao setar item da UR como status ERROR [${itemId}]"])
            }
        }
    }

    public void processChargebackEvents() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.RECEIVABLE_UNIT_ITEM_CHARGEBACK_EVENT, MAX_ITEMS_PER_CYCLE)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                processChargebackEvent(asyncActionData)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "PrepareReceivableUnitItemService.processChargebackEvents >> Falha ao processar evento de chargeback [${asyncActionData.chargebackStatus}] da cobrança [${asyncActionData.paymentId}] AsyncAction [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void processChargebackEvent(Map eventInfo) {
        ChargebackStatus chargebackStatus = ChargebackStatus.valueOf(eventInfo.chargebackStatus)

        if (chargebackStatus.isRequested()) {
            ReceivableUnitItem item = ReceivableUnitItem.query([paymentId: eventInfo.paymentId, "contractualEffect[isNull]": true, "anticipatedReceivableUnit[isNull]": true]).get()
            if (!item) return
            if (item.status.isInSettlementProcess()) return
            if (ChargebackScheduledSettlement.existsPendingSettlement(eventInfo.chargebackId, [paymentId: item.payment.id]).get().asBoolean()) return

            receivableUnitItemService.delete(item)
        } else {
            receivableUnitItemService.processChargebackReversed(eventInfo.paymentId)
        }
    }
}
