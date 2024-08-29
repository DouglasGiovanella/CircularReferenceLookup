package com.asaas.service.integration.cerc.conciliation.contract

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliation
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationDivergentItem
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationSummary
import com.asaas.integration.cerc.adapter.batch.CercContractConciliationSnapshotItemAdapter
import com.asaas.integration.cerc.builder.conciliation.CercContractConciliationSnapshotFileHandler
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercContractConciliationDivergentItemService {

    def asyncActionService

    public void processPendingConciliationDivergences() {
        final Integer maxDivergentConciliationsByExecution = 5
        List<Map> pendingAsyncActionDataList = asyncActionService.listPending(AsyncActionType.PROCESS_CERC_CONTRACT_CONCILIATION_DIVERGENCES, maxDivergentConciliationsByExecution)
        for (Map asyncActionData : pendingAsyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                findAndSaveDivergentItems(asyncActionData.cerContractConciliationId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CercContractConciliationDivergentItemService.processPendingConciliationDivergences >> Não foi possível processar a asyncAction de divergências nas conciliação de contratos : [${asyncActionData.asyncActionId}]",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void findAndSaveDivergentItems(Long cerContractConciliationId) {
        List<CercContractConciliationSnapshotItemAdapter> conciliationItemsList = getItemsFromConciliation(cerContractConciliationId)
        CercContractConciliation conciliation = CercContractConciliation.read(cerContractConciliationId)

        conciliationItemsList.groupBy { it.externalIdentifier }.each { String externalIdentifier, List<CercContractConciliationSnapshotItemAdapter> itemsFromDifferentOrigin ->
            CercContractConciliationSnapshotItemAdapter asaasItem = itemsFromDifferentOrigin.find { it.origin == ReceivableRegistrationConciliationOrigin.ASAAS }
            CercContractConciliationSnapshotItemAdapter cercItem = itemsFromDifferentOrigin.find { it.origin == ReceivableRegistrationConciliationOrigin.CERC }

            if (asaasItem?.value == cercItem?.value) return

            save(conciliation, externalIdentifier, asaasItem, cercItem)
        }
    }

    private List<CercContractConciliationSnapshotItemAdapter> getItemsFromConciliation(Long conciliationId) {
        List<Map> conciliationSummaryList = CercContractConciliationSummary.query([columnList: ["contractsSnapshotFile", "origin"], conciliationId: conciliationId]).list()
        List<CercContractConciliationSnapshotItemAdapter> itemList = []

        for (Map summaryMap : conciliationSummaryList) {
            List<CercContractConciliationSnapshotItemAdapter> itemsFromSummary = CercContractConciliationSnapshotFileHandler.read(summaryMap.contractsSnapshotFile, summaryMap.origin)
            itemList.addAll(itemsFromSummary)
        }

        return itemList
    }

    private void save(CercContractConciliation conciliation, String externalIdentifier, CercContractConciliationSnapshotItemAdapter asaasItem, CercContractConciliationSnapshotItemAdapter cercItem) {
        CercContractConciliationDivergentItem divergentItem = new CercContractConciliationDivergentItem()
        divergentItem.conciliation = conciliation
        divergentItem.externalIdentifier = externalIdentifier
        divergentItem.existsOnAsaasConciliation = asaasItem != null
        divergentItem.asaasValue = asaasItem?.value
        divergentItem.existsOnCercConciliation = cercItem != null
        divergentItem.cercValue = cercItem?.value
        divergentItem.save(failOnError: true)
    }
}
