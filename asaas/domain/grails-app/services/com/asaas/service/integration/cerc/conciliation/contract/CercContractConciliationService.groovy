package com.asaas.service.integration.cerc.conciliation.contract

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliation
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationSummary
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.integration.cerc.adapter.batch.CercContractConciliationItemAdapter
import com.asaas.integration.cerc.enums.CercBatchType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.integration.cerc.enums.contractualeffect.CercContractOperationMode
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import com.asaas.integration.cerc.parser.CercBatchParser
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercContractConciliationService {

    def asyncActionService
    def cercContractConciliationSummaryService

    public void setAsSynced(Long conciliationId) {
        CercContractConciliation conciliation = CercContractConciliation.get(conciliationId)
        conciliation.syncStatus = CercSyncStatus.SYNCED
        conciliation.save(failOnError: true)
    }

    public void setAsError(Long conciliationId) {
        CercContractConciliation conciliation = CercContractConciliation.get(conciliationId)
        conciliation.syncStatus = CercSyncStatus.ERROR
        conciliation.save(failOnError: true)
    }

    public void conciliateAsaasContracts() {
        Date referenceDate = new Date().clearTime()

        Boolean alreadyExistsAsaasConciliationForReferenceDate = CercContractConciliationSummary.query([exists:true, conciliationReferenceDate: referenceDate, origin: ReceivableRegistrationConciliationOrigin.ASAAS]).get().asBoolean()
        if (alreadyExistsAsaasConciliationForReferenceDate) return

        List<CercContractConciliationItemAdapter> contractAdapterList = CercFidcContractualEffect.active([syncStatus: CercSyncStatus.SYNCED]).list()
                .collect { CercFidcContractualEffect item -> new CercContractConciliationItemAdapter(item, referenceDate) }

        conciliate(ReceivableRegistrationConciliationOrigin.ASAAS, contractAdapterList)
    }

    public void conciliateCercBatch(AsaasFile batchFile) {
        CercBatchParser batchParser = new CercBatchParser(batchFile, CercBatchType.CONTRACT_CONCILIATION)
        List<String> requiredFields = ["Referencia_Externa", "Data_Referencia", "Detentor", "Tipo_Efeito", "Modalidade_Operacao", "Contratante", "Saldo_Devedor"]
        Closure toAdapter = { Map item -> new CercContractConciliationItemAdapter(item) }

        List<CercContractConciliationItemAdapter> contractAdapterList = batchParser.parse(requiredFields, toAdapter)

        conciliate(ReceivableRegistrationConciliationOrigin.CERC, contractAdapterList)
    }

    private void conciliate(ReceivableRegistrationConciliationOrigin origin, List<CercContractConciliationItemAdapter> contractList) {
        contractList.groupBy { it.referenceDate }.each { Date referenceDate, List<CercContractConciliationItemAdapter> groupedByReferenceDate ->
            groupedByReferenceDate.groupBy { it.partner }.each { ReceivableAnticipationPartner partner, List<CercContractConciliationItemAdapter> groupedByPartnerList ->
                groupedByPartnerList.groupBy { it.effectType }.each { CercEffectType effectType, List<CercContractConciliationItemAdapter> groupedByEffectType ->
                    groupedByEffectType.groupBy { it.operationMode }.each { CercContractOperationMode operationMode, List<CercContractConciliationItemAdapter> groupedItemsAdapterList ->
                        CercContractConciliation conciliation = CercContractConciliation.findByCompositeKey(referenceDate, partner, effectType, operationMode).get()
                        if (!conciliation) conciliation = save(referenceDate, partner, effectType, operationMode)

                        Integer amountOfContracts = groupedItemsAdapterList.size()
                        Integer amountOfCustomers = groupedItemsAdapterList.groupBy { it.customerCpfCnpj }.size()
                        BigDecimal value = Utils.toBigDecimal(groupedItemsAdapterList*.value.sum())

                        cercContractConciliationSummaryService.save(conciliation, origin, amountOfContracts, amountOfCustomers, value, groupedItemsAdapterList)

                        saveAsyncActionToProcessDivergencesIfNecessary(conciliation)
                    }
                }
            }
        }
    }

    private CercContractConciliation save(Date referenceDate, ReceivableAnticipationPartner partner, CercEffectType effectType, CercContractOperationMode operationMode) {
        CercContractConciliation conciliation = new CercContractConciliation()
        conciliation.referenceDate = referenceDate
        conciliation.partner = partner
        conciliation.effectType = effectType
        conciliation.operationMode = operationMode
        conciliation.syncStatus = CercSyncStatus.AWAITING_SYNC
        conciliation.save(failOnError: true)

        return conciliation
    }

    private void saveAsyncActionToProcessDivergencesIfNecessary(CercContractConciliation conciliation) {
        List<String> fieldsToCompare = ["amountOfContracts", "amountOfCustomers", "value"]
        Map asaasSummaryMap = CercContractConciliationSummary.query([columnList: fieldsToCompare, conciliationId: conciliation.id, origin: ReceivableRegistrationConciliationOrigin.ASAAS]).get()
        if (!asaasSummaryMap) return

        Map cercSummaryMap = CercContractConciliationSummary.query([columnList: fieldsToCompare, conciliationId: conciliation.id, origin: ReceivableRegistrationConciliationOrigin.CERC]).get()
        if (!cercSummaryMap) return

        Boolean conciliationHasDivergences = fieldsToCompare.any { String field -> asaasSummaryMap[field] != cercSummaryMap[field] }
        if (!conciliationHasDivergences) return

        asyncActionService.save(AsyncActionType.PROCESS_CERC_CONTRACT_CONCILIATION_DIVERGENCES, [cerContractConciliationId: conciliation.id])
    }
}
