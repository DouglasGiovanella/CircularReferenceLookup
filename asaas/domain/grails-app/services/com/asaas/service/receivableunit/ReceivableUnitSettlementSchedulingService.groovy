package com.asaas.service.receivableunit

import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.receivableunit.ReceivableUnitStatus
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableUnitSettlementSchedulingService {

    def cercContractualEffectSettlementService
    def processReceivableUnitService
    def receivableUnitItemService

    public void processReceivableUnitsToScheduleSettlement() {
        Integer maxItemsByExecution = 500

        Map search = getDefaultFilterToListReceivableUnitToScheduleSettlement()
        List<Long> receivableUnitToBeScheduledIdList = ReceivableUnit.query(search).list(max: maxItemsByExecution)

        final Integer numberOfThreads = 4
        Utils.processWithThreads(receivableUnitToBeScheduledIdList, numberOfThreads, { List<Long> threadReceivableUnitIdList ->
            for (Long receivableUnitId : threadReceivableUnitIdList) {
                Utils.withNewTransactionAndRollbackOnError({
                    prepareToScheduleSettlement(receivableUnitId)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("ReceivableUnitSettlementSchedulingService.processReceivableUnitsToScheduleSettlement >> Ocorreu um lock ao realizar o agendamento da liquidação da UR [${receivableUnitId}]", exception)
                            return
                        }

                        AsaasLogger.error("ReceivableUnitSettlementSchedulingService.processReceivableUnitsToScheduleSettlement >> Erro ao realizar o agendamento da liquidação da UR [${receivableUnitId}]", exception)
                    }
                ])
            }
        })
    }

    public List<Long> updateReadyToScheduleItemsToSettlementScheduled() {
        Map search = [:]
        search.column = "id"
        search.status = ReceivableUnitItemStatus.READY_TO_SCHEDULE_SETTLEMENT
        search."paymentArrangement[notIn]" = PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement()
        search.estimatedCreditDate = CustomDateUtils.getNextBusinessDay()
        search.receivableUnitStatus = ReceivableUnitStatus.SETTLED
        search.disableSort = true
        List<Long> readyToScheduleSettlementItemsIdList = ReceivableUnitItem.query(search).list()

        if (!readyToScheduleSettlementItemsIdList) return []

        final Integer collateSize = 3000
        for (List<Long> collatedIdList : readyToScheduleSettlementItemsIdList.collate(collateSize)) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem.executeUpdate("""
                    UPDATE ReceivableUnitItem
                        SET status = :status, version = version + 1, lastUpdated = :lastUpdated
                    WHERE id IN (:collatedIdList)
                """, [status: ReceivableUnitItemStatus.SETTLEMENT_SCHEDULED, collatedIdList: collatedIdList, lastUpdated: new Date()])
            }, [logErrorMessage: "ReceivableUnitSettlementSchedulingService.setReadyToScheduleItemsAsScheduled >> Erro ao atualizar o status dos itens [${collatedIdList}]"])
        }

        return readyToScheduleSettlementItemsIdList
    }

    public void updateToScheduledSettlementFromReceivableUnitItemIdList(List<Long> receivableUnitItemIdList) {
        if (!receivableUnitItemIdList) return

        final Integer collateSize = 3000
        for (List<Long> collatedIdList : receivableUnitItemIdList.collate(collateSize)) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitItem.executeUpdate("""
                UPDATE ReceivableUnitItem
                        SET status = :status, version = version + 1, lastUpdated = :lastUpdated
                    WHERE id IN (:collatedIdList)
            """, [status: ReceivableUnitItemStatus.SETTLEMENT_SCHEDULED, collatedIdList: collatedIdList, lastUpdated: new Date()])
            }, [logErrorMessage: "ReceivableUnitSettlementSchedulingService.updateToScheduledSettlementFromReceivableUnitItemIdList >> Erro ao atualizar o status dos itens [${collatedIdList}]"])
        }
    }

    public void prepareToScheduleSettlement(Long receivableUnitId) {
        prepareItemsToScheduleSettlement(receivableUnitId)
        processReceivableUnitService.refreshReceivableUnit(ReceivableUnit.get(receivableUnitId))
    }

    private void prepareItemsToScheduleSettlement(Long receivableUnitId) {
        List<Long> paymentItemsIdList = ReceivableUnitItem.query([
            column: "id",
            receivableUnitId: receivableUnitId,
            status: ReceivableUnitItemStatus.PROCESSED,
            "contractualEffect[isNull]": true
        ]).list()

        final Integer flushEvery = 100
        Utils.forEachWithFlushSession(paymentItemsIdList, flushEvery, { Long paymentItemId ->
            ReceivableUnitItem paymentItem = ReceivableUnitItem.get(paymentItemId)
            receivableUnitItemService.setAsReadyToScheduleSettlement(paymentItem)
            cercContractualEffectSettlementService.useItemToSettleContractsIfNecessary(paymentItem)
        })
    }

    private Map getDefaultFilterToListReceivableUnitToScheduleSettlement() {
        Map search = [:]
        search.column = "id"
        search.estimatedCreditDate = CustomDateUtils.getNextBusinessDay()
        search."operationType" = CercOperationType.UPDATE
        search."processedPaymentItem[exists]" = true
        search."paymentArrangement[in]" = PaymentArrangement.listAllowedToSlcAutomaticProcessArrangement()
        search.disableSort = true

        return search
    }
}
