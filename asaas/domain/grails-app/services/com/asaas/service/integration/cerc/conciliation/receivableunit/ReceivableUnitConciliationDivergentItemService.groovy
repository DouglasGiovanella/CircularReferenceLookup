package com.asaas.service.integration.cerc.conciliation.receivableunit

import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationDivergentItem
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationSummary
import com.asaas.domain.receivableunit.ReceivableUnitConciliation
import com.asaas.integration.cerc.adapter.batch.ReceivableUnitConciliationSnapshotItemAdapter
import com.asaas.integration.cerc.builder.conciliation.ReceivableUnitConciliationSnapshotFileHandler
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableUnitConciliationDivergentItemService {

    def receivableRegistrationEventQueueService

    public void processPendingDivergences() {
        final Integer maxDivergencesPerExecution = 5
        List<Map> pendingAsyncActionDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.PROCESS_RECEIVABLE_UNIT_CONCILIATION_DIVERGENCES, null, null, maxDivergencesPerExecution)

        for (Map eventQueueData : pendingAsyncActionDataList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                findAndSaveDivergentItems(eventQueueData.conciliationId)
                receivableRegistrationEventQueueService.delete(eventQueueData.eventQueueId)
            }, [logErrorMessage: "ReceivableUnitConciliationDivergentItemService.processPendingDivergences >> Erro ao processar as divergências da conciliação [${eventQueueData.conciliationId}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableRegistrationEventQueueService.setAsError(eventQueueData.eventQueueId)
                }, [logErrorMessage: "ReceivableUnitConciliationDivergentItemService.processPendingDivergences --> Falha ao marcar evento como ERROR [${eventQueueData.eventQueueId}]"])
            }
        }
    }

    private void findAndSaveDivergentItems(Long conciliationId) {
        List<ReceivableUnitConciliationSnapshotItemAdapter> snapshotItemAdapterList = getItemsFromConciliation(conciliationId)
        ReceivableUnitConciliation conciliation = ReceivableUnitConciliation.read(conciliationId)

        snapshotItemAdapterList.groupBy { it.externalIdentifier }.each { String externalIdentifier, List<ReceivableUnitConciliationSnapshotItemAdapter> itemsByIdentifier ->
            ReceivableUnitConciliationSnapshotItemAdapter asaasItem = itemsByIdentifier.find { it.origin == ReceivableRegistrationConciliationOrigin.ASAAS }
            ReceivableUnitConciliationSnapshotItemAdapter partnerItem = itemsByIdentifier.find { it.origin == ReceivableRegistrationConciliationOrigin.CERC }

            if (hasDivergences(asaasItem, partnerItem)) save(externalIdentifier, conciliation, asaasItem, partnerItem)
        }
    }

    private List<ReceivableUnitConciliationSnapshotItemAdapter> getItemsFromConciliation(Long conciliationId) {
        List<Map> summaryMapList = ReceivableUnitConciliationSummary.query([columnList: ["snapshotFile", "origin"], conciliationId: conciliationId]).list()

        List<ReceivableUnitConciliationSnapshotItemAdapter> itemList = []

        for (Map summaryMap : summaryMapList) {
            List<ReceivableUnitConciliationSnapshotItemAdapter> snapshotItemList = ReceivableUnitConciliationSnapshotFileHandler.read(summaryMap.snapshotFile, summaryMap.origin)
            itemList.addAll(snapshotItemList)
        }

        return itemList
    }

    private void save(String externalIdentifier, ReceivableUnitConciliation conciliation, ReceivableUnitConciliationSnapshotItemAdapter asaasItemAdapter, ReceivableUnitConciliationSnapshotItemAdapter partnerItemAdapter) {
        ReceivableUnitConciliationDivergentItem divergentItem = new ReceivableUnitConciliationDivergentItem()
        divergentItem.externalIdentifier = externalIdentifier
        divergentItem.conciliation = conciliation

        divergentItem.existsOnAsaasConciliation = asaasItemAdapter.asBoolean()
        divergentItem.asaasTotalValue = asaasItemAdapter?.totalValue
        divergentItem.asaasNetValue = asaasItemAdapter?.netValue
        divergentItem.asaasAnticipatedValue = asaasItemAdapter?.anticipatedValue
        divergentItem.asaasPreAnticipatedValue = asaasItemAdapter?.preAnticipatedValue
        divergentItem.asaasContractualEffectValue = asaasItemAdapter?.contractualEffectValue

        divergentItem.existsOnPartnerConciliation = partnerItemAdapter.asBoolean()
        divergentItem.partnerTotalValue = partnerItemAdapter?.totalValue
        divergentItem.partnerNetValue = partnerItemAdapter?.netValue
        divergentItem.partnerAnticipatedValue = partnerItemAdapter?.anticipatedValue
        divergentItem.partnerPreAnticipatedValue = partnerItemAdapter?.preAnticipatedValue
        divergentItem.partnerContractualEffectValue = partnerItemAdapter?.contractualEffectValue

        divergentItem.save(failOnError: true)
    }

    private Boolean hasDivergences(ReceivableUnitConciliationSnapshotItemAdapter asaasItem, ReceivableUnitConciliationSnapshotItemAdapter partnerItem) {
        if (!asaasItem || !partnerItem) return true
        if (asaasItem.totalValue != partnerItem.totalValue) return true
        if (asaasItem.netValue != partnerItem.netValue) return true
        if (asaasItem.anticipatedValue != partnerItem.anticipatedValue) return true
        if (asaasItem.preAnticipatedValue != partnerItem.preAnticipatedValue) return true
        if (asaasItem.contractualEffectValue != partnerItem.contractualEffectValue) return true

        return false
    }
}
