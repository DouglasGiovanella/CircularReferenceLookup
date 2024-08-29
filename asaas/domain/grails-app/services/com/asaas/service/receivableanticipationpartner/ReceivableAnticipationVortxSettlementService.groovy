package com.asaas.service.receivableanticipationpartner

import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatch
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItem
import com.asaas.integration.vortx.manager.VortxManager
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationVortxSettlementService {

    public void syncSettlements() {
        List<Long> settlementBatchIdList = listPartnerSettlementBatchsReadyToSync()
        if (!settlementBatchIdList) return
        if (settlementBatchIdList.size() > 1) throw new RuntimeException("ReceivableAnticipationVortxSettlementService.syncSettlements >> Existem 2 ou mais remessas pendentes de sincronização. É necessário enviar manualmente e aguardar aprovação no FIDC.")

        Long settlementBatchId = settlementBatchIdList[0]

        Utils.withNewTransactionAndRollbackOnError({
            ReceivableAnticipationPartnerSettlementBatch settlementBatch = ReceivableAnticipationPartnerSettlementBatch.get(settlementBatchId)

            Long bankDepositId = findBankDepositId(settlementBatch)

            Map settlementListsToBeSend = getListsOfSettlementItems(settlementBatch)

            Boolean hasAnySynchronizationFailed = false
            final Integer maxNumberOfItemsPerSynchronization = 500
            settlementListsToBeSend.partialSettlementItemList.collate(maxNumberOfItemsPerSynchronization).each {
                List<Long> items = it.collect()
                if (!syncPartialSettlements(bankDepositId, items)) hasAnySynchronizationFailed = true
            }

            if (!syncFullSettlements(bankDepositId, settlementListsToBeSend.partialAndFullSettlementItemsListForTheSameBatchAndPartnerSettlement)) hasAnySynchronizationFailed = true

            settlementListsToBeSend.fullSettlementItemList.collate(maxNumberOfItemsPerSynchronization).each {
                List<Long> items = it.collect()
                if (!syncFullSettlements(bankDepositId, items)) hasAnySynchronizationFailed = true
            }

            if (hasAnySynchronizationFailed) return

            settlementBatch.awaitingSendSettlementItems = false
            settlementBatch.save(failOnError: true)
        }, [logErrorMessage: "VortxSettlement >> Erro na sincronização dos items de liquidação da remessa [${settlementBatchId}]"])
    }

    private List<Long> listPartnerSettlementBatchsReadyToSync() {
        Map search = [
            column: "id",
            awaitingSendSettlementItems: true,
            "transmissionDate[le]": new Date().clearTime(),
            sort: "id",
            order: "asc"
        ]

        return ReceivableAnticipationPartnerSettlementBatch.done(search).list()
    }

    private Long findBankDepositId(ReceivableAnticipationPartnerSettlementBatch settlementBatch) {
        VortxManager vortxManager = new VortxManager()
        List<Map> bankDepositList = vortxManager.getBankDepositList()
        if (!bankDepositList) throw new Exception("Não foi encontrado nenhum depósito bancário")

        BigDecimal settlementBatchTotalValue = settlementBatch.getItemsValue()
        Map bankDeposit = bankDepositList.find { Utils.toBigDecimal(it.valorLancamento) == settlementBatchTotalValue }
        if (bankDeposit) return bankDeposit.id

        bankDeposit = bankDepositList.find { CustomDateUtils.fromStringDatabaseDateFormat(it.dataProvisao.toString()).clearTime() == settlementBatch.transmissionDate.clearTime() }
        if (bankDeposit) {
            List<ReceivableAnticipationPartnerSettlementBatch> settlementBatchList = ReceivableAnticipationPartnerSettlementBatch.done(["transmissionDate[ge]": settlementBatch.transmissionDate.clearTime(), "transmissionDate[le]": CustomDateUtils.setTimeToEndOfDay(settlementBatch.transmissionDate)]).list()
            settlementBatchTotalValue = 0
            for (ReceivableAnticipationPartnerSettlementBatch batch in settlementBatchList) {
                settlementBatchTotalValue += batch.getItemsValue()
            }

            if (Utils.toBigDecimal(bankDeposit.valorLancamento) == settlementBatchTotalValue) return bankDeposit.id
        }

        throw new Exception("Não foi encontrado depósito bancário para a remessa [${settlementBatch.id}]")
    }

    private Map getListsOfSettlementItems(ReceivableAnticipationPartnerSettlementBatch settlementBatch) {
        Map search = [paid: true, partnerSettlementBatch: settlementBatch]
        List<ReceivableAnticipationPartnerSettlementItem> settlementItemList = ReceivableAnticipationPartnerSettlementItem.vortxItemAwaitingSendByApi(search).list()

        List<Long> partialSettlementItemList = []
        List<Long> fullSettlementItemList = []
        List<Long> partialAndFullSettlementItemsListForTheSameBatchAndPartnerSettlement = []

        Map partnerSettlementItemListGroupedBySettlement = settlementItemList.groupBy { it.partnerSettlement.id }
        partnerSettlementItemListGroupedBySettlement.each { Long settlementId, List<ReceivableAnticipationPartnerSettlementItem> settlementItems ->
            Boolean hasPartialItems = settlementItems.any { it.type.isPartial() }
            Boolean hasFullItems = settlementItems.any { it.type.isFull() }

            if (hasPartialItems && hasFullItems) {
                partialAndFullSettlementItemsListForTheSameBatchAndPartnerSettlement.addAll(settlementItems.collect { it.id })
                return
            }

            if (hasPartialItems) {
                partialSettlementItemList.addAll(settlementItems.collect { it.id })
                return
            }

            fullSettlementItemList.addAll(settlementItems.collect { it.id })
        }

        return [
            partialSettlementItemList: partialSettlementItemList,
            fullSettlementItemList: fullSettlementItemList,
            partialAndFullSettlementItemsListForTheSameBatchAndPartnerSettlement: partialAndFullSettlementItemsListForTheSameBatchAndPartnerSettlement
        ]
    }

    private Boolean syncPartialSettlements(Long bankDepositId, List<Long> settlementItemIdList) {
        Boolean isSynchronized = false

        Utils.withNewTransactionAndRollbackOnError({
            if (!settlementItemIdList) {
                isSynchronized = true
                return
            }

            List<ReceivableAnticipationPartnerSettlementItem> settlementItemList = ReceivableAnticipationPartnerSettlementItem.query(["id[in]": settlementItemIdList]).list()

            VortxManager vortxManager = new VortxManager()
            String partnerBatchId = vortxManager.transmitSettlementBatch(settlementItemList, bankDepositId, true)

            setItemsAsSent(settlementItemList, partnerBatchId)

            isSynchronized = true
        }, [logErrorMessage: "VortxSettlement >> Erro na sincronização dos items de liquidação parcial"])

        if (!isSynchronized) setItemAsErrorAndTemporaryBatchIdWithNewTransaction(settlementItemIdList)

        return isSynchronized
    }

    private Boolean syncFullSettlements(Long bankDepositId, List<Long> settlementItemIdList) {
        Boolean isSynchronized = false

        Utils.withNewTransactionAndRollbackOnError({
            if (!settlementItemIdList) {
                isSynchronized = true
                return
            }

            List<ReceivableAnticipationPartnerSettlementItem> settlementItemList = ReceivableAnticipationPartnerSettlementItem.query(["id[in]": settlementItemIdList]).list()

            VortxManager vortxManager = new VortxManager()
            String partnerBatchId = vortxManager.transmitSettlementBatch(settlementItemList, bankDepositId, false)

            setItemsAsSent(settlementItemList, partnerBatchId)

            isSynchronized = true
        }, [logErrorMessage: "VortxSettlement >> Erro na sincronização dos items de liquidação total"])

        if (!isSynchronized) setItemAsErrorAndTemporaryBatchIdWithNewTransaction(settlementItemIdList)

        return isSynchronized
    }

    private void setItemsAsSent(List<ReceivableAnticipationPartnerSettlementItem> settlementItemList, String partnerBatchId) {
        for (ReceivableAnticipationPartnerSettlementItem settlementItem in settlementItemList) {
            settlementItem.status = ReceivableAnticipationPartnerSettlementItemStatus.SENT
            settlementItem.receivableAnticipationPartnerSettlementBatchItem.partnerBatchId = partnerBatchId
            settlementItem.save(failOnError: true)
        }
    }

    private void setItemAsErrorAndTemporaryBatchIdWithNewTransaction(List<Long> settlementItemIdList) {
        String temporaryPartnerBatchId = UUID.randomUUID().toString()
        for (Long settlementItemId in settlementItemIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationPartnerSettlementItem settlementItem = ReceivableAnticipationPartnerSettlementItem.get(settlementItemId)
                settlementItem.status = ReceivableAnticipationPartnerSettlementItemStatus.ERROR
                settlementItem.receivableAnticipationPartnerSettlementBatchItem.partnerBatchId = temporaryPartnerBatchId
                settlementItem.save(failOnError: true)
            }, [logErrorMessage: "VortxSettlement >> Erro ao marcar item de liquidação [${settlementItemId}] com o status ERROR"])
        }
    }
}
