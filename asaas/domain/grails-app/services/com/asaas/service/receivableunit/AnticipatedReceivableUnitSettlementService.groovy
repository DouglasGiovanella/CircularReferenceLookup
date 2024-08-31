package com.asaas.service.receivableunit

import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.receivableregistration.ReceivableRegistrationEventQueue
import com.asaas.domain.receivableunit.AnticipatedReceivableUnitSettlement
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueStatus
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class AnticipatedReceivableUnitSettlementService {

    def receivableRegistrationEventQueueService
    def receivableUnitService

    public void processPendingCalculateAnticipatedReceivableUnitSettlements() {
        final Integer maxAmountOfDataPerExecution = 800

        Map search = [:]
        search.distinct = "groupId"
        search.disableSort = true
        search.type = ReceivableRegistrationEventQueueType.CALCULATE_ANTICIPATED_RECEIVABLE_UNIT_SETTLEMENTS
        search.status = ReceivableRegistrationEventQueueStatus.PENDING
        List<String> groupIdList = ReceivableRegistrationEventQueue.query(search).list(max: maxAmountOfDataPerExecution)

        final Integer maxItemsOnList = 100
        final Integer itemsPerThread = 200

        ThreadUtils.processWithThreadsOnDemand(groupIdList, itemsPerThread, { List<String> subGroupIdList ->
            List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.CALCULATE_ANTICIPATED_RECEIVABLE_UNIT_SETTLEMENTS, subGroupIdList, null, maxItemsOnList)

            Utils.forEachWithFlushSession(eventDataList, 50, { Map eventData ->
                Boolean hasError = false

                Utils.withNewTransactionAndRollbackOnError({
                    ReceivableUnit receivableUnit = ReceivableUnit.lock(eventData.receivableUnitId)
                    receivableUnitService.consolidate(receivableUnit)

                    refreshForReceivableUnit(receivableUnit, CustomDateUtils.fromString(eventData.anticipatedDate))

                    receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (Utils.isLock(exception)) {
                            AsaasLogger.warn("AnticipatedReceivableUnitSettlementService.processPendingCalculateAnticipatedReceivableUnitSettlements >> Ocorreu um lock ao calcular pagamentos de pós contratadas do evento [${eventData.eventQueueId}]", exception)
                            return
                        }

                        AsaasLogger.error("AnticipatedReceivableUnitSettlementService.processPendingCalculateAnticipatedReceivableUnitSettlements >> Erro ao calcular pagamentos de pós contratadas do evento [${eventData.eventQueueId}]", exception)
                        hasError = true
                    }
                ])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                    }, [logErrorMessage: "AnticipatedReceivableUnitSettlementService.processPendingCalculateAnticipatedReceivableUnitSettlements >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
                }
            })
        })
    }

    private AnticipatedReceivableUnitSettlement save(ReceivableUnit receivableUnit, CercBankAccount bankAccount, Date settlementDate) {
        AnticipatedReceivableUnitSettlement validatedDomain = validateSave(receivableUnit, bankAccount, settlementDate)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar pagamento de pós contratada", validatedDomain.errors)

        AnticipatedReceivableUnitSettlement settlement = new AnticipatedReceivableUnitSettlement()
        settlement.receivableUnit = receivableUnit
        settlement.bankAccount = bankAccount
        settlement.settlementDate = settlementDate
        settlement.save(failOnError: true)

        return settlement
    }

    private AnticipatedReceivableUnitSettlement validateSave(ReceivableUnit receivableUnit, CercBankAccount bankAccount, Date settlementDate) {
        AnticipatedReceivableUnitSettlement validatedDomain = new AnticipatedReceivableUnitSettlement()

        Map search = [:]
        search.exists = true
        search.disableSort = true
        Boolean alreadyExists = AnticipatedReceivableUnitSettlement.findByCompositeKey(receivableUnit, bankAccount, settlementDate, search).get().asBoolean()
        if (alreadyExists) DomainUtils.addError(validatedDomain, "Já existe um pagamento de pós contratada para a UR [${receivableUnit.id}] na data [${settlementDate}] para a conta [${bankAccount.id}]")

        return validatedDomain
    }

    private void refreshForReceivableUnit(ReceivableUnit receivableUnit, Date settlementDate) {
        List<Map> settlementInfoList = getSettlementsInfoFromConstitutedItems(receivableUnit)

        for (Map settlementInfo : settlementInfoList) {
            AnticipatedReceivableUnitSettlement settlement = AnticipatedReceivableUnitSettlement.findByCompositeKey(receivableUnit, settlementInfo.bankAccount, settlementDate, [disableSort: true]).get()
            if (!settlement) settlement = save(receivableUnit, settlementInfo.bankAccount, settlementDate)

            recalculateSettledValue(settlement, settlementDate)
        }
    }

    private void recalculateSettledValue(AnticipatedReceivableUnitSettlement settlement, Date settlementDate) {
        Map search = [receivableUnitId: settlement.receivableUnit.id, bankAccountId: settlement.bankAccount.id, "netValue[gt]": 0.0, "anticipatedReceivableUnitAnticipationCreditDate": settlementDate]
        settlement.settledValue = ReceivableUnitItem.sumAnticipatedNetValue(search).get()

        settlement.save(failOnError: true)
    }

    private List<Map> getSettlementsInfoFromConstitutedItems(ReceivableUnit receivableUnit) {
        Map search = [:]
        search.receivableUnitId = receivableUnit.id
        search."status[in]" = ReceivableUnitItemStatus.listConstituted()
        search."netValue[gt]" = 0.0
        search."anticipatedReceivableUnitId[isNotNull]" = true
        search.disableSort = true

        return ReceivableUnitItem.groupedByBankAccountAndContractualEffect(search).list()
    }
}
