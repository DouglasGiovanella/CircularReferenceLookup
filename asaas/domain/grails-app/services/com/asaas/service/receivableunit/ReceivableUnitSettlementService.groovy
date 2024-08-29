package com.asaas.service.receivableunit

import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableregistration.ReceivableRegistrationEventQueue
import com.asaas.domain.receivableunit.AnticipatedReceivableUnitSettlement
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.domain.receivableunit.ReceivableUnitSettlement
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueStatus
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivableUnitSettlementService {

    def receivableRegistrationEventQueueService
    def receivableRegistrationSynchronizationEventQueueService

    public void processPendingCalculateReceivableUnitSettlementsEventQueue() {
        final Integer maxNumberOfGroupIdPerExecution = 800

        Map search = [:]
        search.distinct = "groupId"
        search.status = ReceivableRegistrationEventQueueStatus.PENDING
        search.disableSort = true
        search.type = ReceivableRegistrationEventQueueType.CALCULATE_RECEIVABLE_UNIT_SETTLEMENTS
        List<String> groupIdList = ReceivableRegistrationEventQueue.query(search).list(max: maxNumberOfGroupIdPerExecution)

        final Integer maxEventsPerThread = 100
        ThreadUtils.processWithThreadsOnDemand(groupIdList, maxEventsPerThread, { List<String> subGroupIdList ->
            List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.CALCULATE_RECEIVABLE_UNIT_SETTLEMENTS, subGroupIdList, null, maxEventsPerThread)

            for (Map eventData : eventDataList) {
                Boolean hasError = false

                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableUnit receivableUnit = ReceivableUnit.get(eventData.receivableUnitId)
                    receivableUnit.lock()
                    refreshForReceivableUnit(receivableUnit)
                    receivableRegistrationSynchronizationEventQueueService.saveIfNecessary(receivableUnit.id, ReceivableRegistrationSynchronizationEventQueueName.RECEIVABLE_UNIT)

                    receivableRegistrationEventQueueService.updateAsProcessed(eventData.eventQueueId)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("ReceivableUnitSettlementService.processPendingCalculateReceivableUnitSettlementsEventQueue >> Ocorreu um lock ao processar evento [${eventData.eventQueueId}]", exception)
                            return
                        }

                        AsaasLogger.error("ReceivableUnitSettlementService.processPendingCalculateReceivableUnitSettlementsEventQueue >> Falha ao processar evento [${eventData.eventQueueId}]", exception)
                        hasError = true
                    }
                ])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                    }, [logErrorMessage: "ReceivableUnitSettlementService.processPendingCalculateReceivableUnitSettlementsEventQueue >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
                }
            }
        })
    }

    private void refreshForReceivableUnit(ReceivableUnit receivableUnit) {
        List<ReceivableUnitSettlement> activeSettlementList = buildFromNonAnticipatedReceivableUnitSettlements(receivableUnit) + buildFromAnticipatedReceivableUnitSettlements(receivableUnit)

        deleteInactiveReceivableUnitSettlements(receivableUnit, activeSettlementList)
    }

    private void deleteInactiveReceivableUnitSettlements(ReceivableUnit receivableUnit, List<ReceivableUnitSettlement> activeSettlementList) {
        Map inactiveSettlementSearch = [:]
        inactiveSettlementSearch.receivableUnitId = receivableUnit.id
        if (activeSettlementList) inactiveSettlementSearch."id[notIn]" = activeSettlementList.collect { it.id }

        List<ReceivableUnitSettlement> inactiveSettlementList = ReceivableUnitSettlement.query(inactiveSettlementSearch).list()

        for (ReceivableUnitSettlement settlement : inactiveSettlementList) {
            settlement.delete(failOnError: true)
        }
    }

    private List<ReceivableUnitSettlement> buildFromNonAnticipatedReceivableUnitSettlements(ReceivableUnit receivableUnit) {
        List<Map> settlementsInfoList = buildInfoFromNonAnticipatedItems(receivableUnit)

        return settlementsInfoList.collect { settlementInfo ->
            ReceivableUnitSettlement settlement = ReceivableUnitSettlement.findByCompositeKey(receivableUnit, settlementInfo.bankAccount, settlementInfo.contractualEffect, null).get()
            if (!settlement) settlement = save(receivableUnit, settlementInfo.bankAccount, settlementInfo.contractualEffect, null)

            recalculateNonAnticipated(settlement)

            return settlement
        }
    }

    private List<ReceivableUnitSettlement> buildFromAnticipatedReceivableUnitSettlements(ReceivableUnit receivableUnit) {
        List<AnticipatedReceivableUnitSettlement> anticipatedReceivableUnitSettlementList = AnticipatedReceivableUnitSettlement.query([receivableUnitId: receivableUnit.id, disableSort: true]).list()

        List<ReceivableUnitSettlement> receivableUnitSettlementList = []
        for (AnticipatedReceivableUnitSettlement anticipatedSettlement : anticipatedReceivableUnitSettlementList) {
            ReceivableUnitSettlement settlement = ReceivableUnitSettlement.findByCompositeKey(receivableUnit, anticipatedSettlement.bankAccount, null, anticipatedSettlement).get()
            if (!settlement) settlement = save(receivableUnit, anticipatedSettlement.bankAccount, null, anticipatedSettlement)

            recalculateWithAnticipatedReceivableUnit(settlement)

            receivableUnitSettlementList.add(settlement)
        }

        return receivableUnitSettlementList
    }

    private void recalculateNonAnticipated(ReceivableUnitSettlement settlement) {
        Map itemsSearch = getReceivableUnitItemsFilter(settlement)

        settlement.unsettledValue = ReceivableUnitItem.sumNetValue([status: ReceivableUnitItemStatus.PROCESSED] + itemsSearch).get()
        if (!settlement.contractualEffect) settlement.unsettledValue = BigDecimalUtils.max(settlement.unsettledValue - getValueReservedByContractualEffect(settlement.receivableUnit), 0)

        settlement.settledValue = ReceivableUnitItem.sumNetValue(["status[in]": ReceivableUnitItemStatus.listInSettlementProcess()] + itemsSearch).get()
        if (settlement.settledValue) settlement.settlementDate = getSettlementDate(settlement)

        settlement.nonPaymentReason = ReceivableUnitItem.query([column: "nonPaymentReason", status: ReceivableUnitItemStatus.SETTLEMENT_DENIED] + itemsSearch).get()

        settlement.save(failOnError: true)
    }

    private void recalculateWithAnticipatedReceivableUnit(ReceivableUnitSettlement settlement) {
        Map itemsSearch = getReceivableUnitItemsFilter(settlement)
        itemsSearch.status = ReceivableUnitItemStatus.PROCESSED

        settlement.unsettledValue = ReceivableUnitItem.sumNetValue(itemsSearch).get()

        settlement.settledValue = settlement.anticipatedReceivableUnitSettlement.settledValue
        settlement.settlementDate = getSettlementDate(settlement)

        settlement.save(failOnError: true)
    }

    private ReceivableUnitSettlement save(ReceivableUnit receivableUnit, CercBankAccount bankAccount, CercContractualEffect contractualEffect, AnticipatedReceivableUnitSettlement anticipatedReceivableUnitSettlement) {
        ReceivableUnitSettlement validatedDomain = validateSave(receivableUnit, bankAccount, contractualEffect, anticipatedReceivableUnitSettlement)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível salvar o pagamento da UR ${receivableUnit.id} para a conta ${bankAccount.id}", validatedDomain.errors)

        ReceivableUnitSettlement settlement = new ReceivableUnitSettlement()
        settlement.receivableUnit = receivableUnit
        settlement.bankAccount = bankAccount
        settlement.contractualEffect = contractualEffect
        settlement.anticipatedReceivableUnitSettlement = anticipatedReceivableUnitSettlement
        settlement.unsettledValue = 0.00
        settlement.save(failOnError: true)

        return settlement
    }

    private ReceivableUnitSettlement validateSave(ReceivableUnit receivableUnit, CercBankAccount bankAccount, CercContractualEffect contractualEffect, AnticipatedReceivableUnitSettlement anticipatedReceivableUnitSettlement) {
        ReceivableUnitSettlement validatedDomain = new ReceivableUnitSettlement()

        Boolean alreadyExists = ReceivableUnitSettlement.findByCompositeKey(receivableUnit, bankAccount, contractualEffect, anticipatedReceivableUnitSettlement).get().asBoolean()
        if (alreadyExists) DomainUtils.addError(validatedDomain, "Já existe um pagamento para a conta ${bankAccount.id} e efeito ${contractualEffect?.id} da UR ${receivableUnit.id}")

        return validatedDomain
    }

    private List<Map> buildInfoFromNonAnticipatedItems(ReceivableUnit receivableUnit) {
        Map search = [:]
        search.receivableUnitId = receivableUnit.id
        search."status[in]" = ReceivableUnitItemStatus.listConstituted()
        search."netValue[gt]" = 0.0
        search.disableSort = true
        search."anticipatedReceivableUnit[isNull]" = true

        return ReceivableUnitItem.groupedByBankAccountAndContractualEffect(search).list()
    }

    private BigDecimal getValueReservedByContractualEffect(ReceivableUnit receivableUnit) {
        Map search = [:]
        search."originalReceivableUnitId" = receivableUnit.id
        search.status = ReceivableUnitItemStatus.PROCESSED
        search."netValue[gt]" = 0.0
        search."contractualEffect[isNotNull]" = true

        return ReceivableUnitItem.sumValue(search).get()
    }

    private Date getSettlementDate(ReceivableUnitSettlement settlement) {
        if (settlement.anticipatedReceivableUnitSettlement) return settlement.anticipatedReceivableUnitSettlement.settlementDate

        Map itemsSearch = getReceivableUnitItemsFilter(settlement)
        Map itemInfo = ReceivableUnitItem.query([columnList: ["estimatedCreditDate", "creditDate"], "status[in]": ReceivableUnitItemStatus.listInSettlementProcess()] + itemsSearch).get()
        if (!itemInfo) return null

        return itemInfo.creditDate ?: itemInfo.estimatedCreditDate
    }

    private Map getReceivableUnitItemsFilter(ReceivableUnitSettlement settlement) {
        Map itemsSearch = [:]
        itemsSearch.receivableUnitId = settlement.receivableUnit.id
        itemsSearch.bankAccountId = settlement.bankAccount.id
        itemsSearch."netValue[gt]" = 0.0

        if (settlement.contractualEffect) {
            itemsSearch.contractualEffectId = settlement.contractualEffect.id
        } else {
            itemsSearch."contractualEffect[isNull]" = true
        }

        if (settlement.anticipatedReceivableUnitSettlement) {
            itemsSearch."anticipatedReceivableUnit[isNotNull]" = true
            itemsSearch.creditDate = settlement.anticipatedReceivableUnitSettlement.settlementDate
        } else {
            itemsSearch."anticipatedReceivableUnit[isNull]" = true
        }

        return itemsSearch
    }
}
