package com.asaas.service.integration.cerc.batch.receivableunitconciliation

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ActiveReceivableUnitSnapshot
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationSummary
import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitConciliation
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.adapter.batch.ReceivableUnitConciliationItemAdapter
import com.asaas.integration.cerc.enums.CercBatchType
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.integration.cerc.parser.CercBatchParser
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.anticipatedreceivableunit.AnticipatedReceivableUnitStatus
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ReceivableUnitConciliationService {

    def activeReceivableUnitSnapshotService
    def receivableRegistrationEventQueueService
    def receivableUnitConciliationSummaryService

    public void conciliateAsaasReceivableUnits() {
        Date referenceDate = new Date().clearTime()

        Boolean conciliationAlreadyExists = ReceivableUnitConciliationSummary.query([exists: true, conciliationReferenceDate: referenceDate, origin: ReceivableRegistrationConciliationOrigin.ASAAS]).get().asBoolean()
        if (conciliationAlreadyExists) return

        List<String> columnList = ["receivableUnitId", "externalIdentifier", "paymentArrangement", "netValue", "totalValue", "notSettledContractualEffectValue"]
        List<Map> asaasActiveReceivableUnitSnapshotList = ActiveReceivableUnitSnapshot.query([columnList: columnList, snapshotDate: referenceDate, disableSort: true]).list()

        List<ReceivableUnitConciliationItemAdapter> conciliationAdapterList = []
        for (Map activeReceivableUnitData : asaasActiveReceivableUnitSnapshotList) {
            activeReceivableUnitData.referenceDate = referenceDate
            activeReceivableUnitData.acquirerCnpj = AsaasApplicationHolder.config.asaas.cnpj.substring(1)
            activeReceivableUnitData.anticipatedValue = BigDecimal.ZERO

            List<Long> anticipatedReceivableUnitIdList = ReceivableUnitItem.query([column: "anticipatedReceivableUnit.id", disableSort: true, "anticipatedReceivableUnitId[isNotNull]": true, receivableUnitId: activeReceivableUnitData.receivableUnitId]).list()
            if (anticipatedReceivableUnitIdList) activeReceivableUnitData.anticipatedValue = AnticipatedReceivableUnit.sumNetValue(["id[in]": anticipatedReceivableUnitIdList, disableSort: true, status: AnticipatedReceivableUnitStatus.ANTICIPATED, operationType: CercOperationType.FINISH]).get()

            activeReceivableUnitData.value = activeReceivableUnitData.totalValue
            activeReceivableUnitData.preAnticipatedValue = BigDecimal.ZERO
            activeReceivableUnitData.contractualEffectValue = activeReceivableUnitData.notSettledContractualEffectValue
            conciliationAdapterList.add(new ReceivableUnitConciliationItemAdapter(activeReceivableUnitData))
        }

        conciliate(ReceivableRegistrationConciliationOrigin.ASAAS, conciliationAdapterList)

        activeReceivableUnitSnapshotService.deleteAll()
    }

    public void conciliateBatch(AsaasFile batch) {
        CercBatchParser parser = new CercBatchParser(batch, CercBatchType.RECEIVABLE_UNIT_CONCILIATION_ANALYTICS_REPORT)

        List<String> requiredFieldNames = ["externalIdentifier", "referenceDate", "acquirerCnpj", "paymentArrangement",
                                   "value", "netValue", "preAnticipatedValue", "anticipatedValue", "contractualEffectValue"]
        List<ReceivableUnitConciliationItemAdapter> adapterList = parser.parse(requiredFieldNames, { Map item ->
            item.referenceDate = CustomDateUtils.fromStringDatabaseDateFormat(item."referenceDate")
            return new ReceivableUnitConciliationItemAdapter(item)
        })

        conciliate(ReceivableRegistrationConciliationOrigin.CERC, adapterList)
    }

    public void setAsSynced(Long conciliationId) {
        ReceivableUnitConciliation conciliation = ReceivableUnitConciliation.get(conciliationId)
        conciliation.syncStatus = CercSyncStatus.SYNCED
        conciliation.save(failOnError: true)
    }

    public void setAsError(Long conciliationId) {
        ReceivableUnitConciliation conciliation = ReceivableUnitConciliation.get(conciliationId)
        conciliation.syncStatus = CercSyncStatus.ERROR
        conciliation.save(failOnError: true)
    }

    private void conciliate(ReceivableRegistrationConciliationOrigin origin, List<ReceivableUnitConciliationItemAdapter> conciliationAdapterList) {
        Date referenceDate = conciliationAdapterList.first().referenceDate
        String acquirerCnpj = AsaasApplicationHolder.getConfig().asaas.cnpj.substring(1)
        conciliationAdapterList.groupBy { it.paymentArrangement }.each { PaymentArrangement paymentArrangement, List<ReceivableUnitConciliationItemAdapter> groupedByArrangement ->
            ReceivableUnitConciliation conciliation = ReceivableUnitConciliation.findByCompositeKey(referenceDate, paymentArrangement, acquirerCnpj).get()
            if (!conciliation) conciliation = save(referenceDate, acquirerCnpj, paymentArrangement)

            BigDecimal netValue = 0.00
            BigDecimal totalValue = 0.00
            BigDecimal preAnticipatedValue = 0.00
            BigDecimal anticipatedValue = 0.00
            BigDecimal contractualEffectValue = 0.00

            for (ReceivableUnitConciliationItemAdapter item : groupedByArrangement) {
                netValue += item.netValue
                totalValue += item.totalValue
                preAnticipatedValue += item.preAnticipatedValue
                anticipatedValue += item.anticipatedValue
                contractualEffectValue += item.contractualEffectValue
            }

            receivableUnitConciliationSummaryService.save(conciliation, origin, netValue, totalValue, preAnticipatedValue, anticipatedValue, contractualEffectValue, groupedByArrangement.size(), groupedByArrangement)

            saveAsyncActionToProcessDivergencesIfNecessary(conciliation)
        }
    }

    private ReceivableUnitConciliation save(Date referenceDate, String acquirerCnpj, PaymentArrangement paymentArrangement) {
        ReceivableUnitConciliation receivableUnitConciliation = new ReceivableUnitConciliation()
        receivableUnitConciliation.referenceDate = referenceDate
        receivableUnitConciliation.acquirerCnpj = acquirerCnpj
        receivableUnitConciliation.paymentArrangement = paymentArrangement
        receivableUnitConciliation.syncStatus = CercSyncStatus.AWAITING_SYNC
        receivableUnitConciliation.save(failOnError: true)

        return receivableUnitConciliation
    }

    private void saveAsyncActionToProcessDivergencesIfNecessary(ReceivableUnitConciliation conciliation) {
        List<String> fieldsToCompare = ["netValue", "totalValue", "preAnticipatedValue", "anticipatedValue", "contractualEffectValue", "amountOfReceivableUnits"]

        Map search = [columnList: fieldsToCompare, conciliationId: conciliation.id]
        Map asaasSummaryMap = ReceivableUnitConciliationSummary.query(search + [origin: ReceivableRegistrationConciliationOrigin.ASAAS]).get()
        if (!asaasSummaryMap) return

        Map cercSummaryMap = ReceivableUnitConciliationSummary.query(search + [origin: ReceivableRegistrationConciliationOrigin.CERC]).get()
        if (!cercSummaryMap) return

        Boolean hasDivergences = fieldsToCompare.any { String fieldName -> asaasSummaryMap[fieldName] != cercSummaryMap[fieldName] }
        if (!hasDivergences) return

        Map eventData = [conciliationId: conciliation.id]
        receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.PROCESS_RECEIVABLE_UNIT_CONCILIATION_DIVERGENCES, eventData, eventData.encodeAsMD5())
    }
}
