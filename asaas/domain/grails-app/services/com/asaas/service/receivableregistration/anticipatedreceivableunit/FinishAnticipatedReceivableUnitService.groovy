package com.asaas.service.receivableregistration.anticipatedreceivableunit

import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.anticipatedreceivableunit.AnticipatedReceivableUnitStatus
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinishAnticipatedReceivableUnitService {

    def anticipatedReceivableUnitService
    def receivableRegistrationEventQueueService
    def receivableRegistrationSynchronizationEventQueueService
    def receivableUnitItemService

    public void processPendingFinishAnticipatedReceivableUnitEventQueue() {
        final Integer maxEventsByExecution = 200
        List<Map> eventDataList = receivableRegistrationEventQueueService.listPendingEventData(ReceivableRegistrationEventQueueType.FINISH_ANTICIPATED_RECEIVABLE_UNIT, null, null, maxEventsByExecution)

        final Integer minItemsPerThread = 50
        ThreadUtils.processWithThreadsOnDemand(eventDataList, minItemsPerThread, { List<Map> subEventDataList ->
            for (Map eventData : subEventDataList) {
                Boolean hasError = false

                Utils.withNewTransactionAndRollbackOnError({
                    AnticipatedReceivableUnit anticipatedReceivableUnit = AnticipatedReceivableUnit.get(eventData.anticipatedReceivableUnitId)
                    settleItemsIfPossible(anticipatedReceivableUnit)

                    receivableRegistrationEventQueueService.delete(eventData.eventQueueId)
                }, [
                    logErrorMessage: "FinishAnticipatedReceivableUnitService.processPendingFinishAnticipatedReceivableUnitEventQueue >> Erro ao finalizar pós contratada do evento [${eventData.eventQueueId}]",
                    onError: { hasError = true }
                ])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationEventQueueService.setAsError(eventData.eventQueueId)
                    }, [logErrorMessage: "FinishAnticipatedReceivableUnitService.processPendingFinishAnticipatedReceivableUnitEventQueue >> Falha ao marcar evento como ERROR [${eventData.eventQueueId}]"])
                }
            }
        })
    }

    public Boolean generateFinishAnticipatedReceivableUnitEvents() {
        Map search = [:]
        search.column = "id"
        search.disableSort = true
        search.operationType = CercOperationType.UPDATE
        search.status = AnticipatedReceivableUnitStatus.REGISTERED
        search.estimatedCreditDate = new Date().clearTime()

        List<Long> anticipatedReceivableUnitIdList = AnticipatedReceivableUnit.query(search).list(max: 500)
        if (!anticipatedReceivableUnitIdList) return false

        final Integer minItemsPerThread = 100
        ThreadUtils.processWithThreadsOnDemand(anticipatedReceivableUnitIdList, minItemsPerThread, { List<Long> idList ->
            for (Long id : idList) {
                Boolean hasError = false

                Utils.withNewTransactionAndRollbackOnError({
                    anticipatedReceivableUnitService.updateOperationTypeAsFinish(id)
                    receivableRegistrationSynchronizationEventQueueService.saveIfNecessary(id, ReceivableRegistrationSynchronizationEventQueueName.ANTICIPATED_RECEIVABLE_UNIT)
                }, [
                    logErrorMessage: "FinishAnticipatedReceivableUnitService.generateFinishAnticipatedReceivableUnitEvents >> Erro ao gerar evento para finalizar pós contratada [${id}]",
                    onError: { hasError = true }
                ])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        anticipatedReceivableUnitService.updateStatusAsError(id)
                    }, [logErrorMessage: "FinishAnticipatedReceivableUnitService.generateFinishAnticipatedReceivableUnitEvents >> Falha ao marcar pós-contratada como ERROR [${id}]"])
                }
            }
        })

        return true
    }

    private void settleItemsIfPossible(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        List<ReceivableUnitItem> receivableUnitItemList = ReceivableUnitItem.query([disableSort: true, anticipatedReceivableUnitId: anticipatedReceivableUnit.id]).list()
        for (ReceivableUnitItem item : receivableUnitItemList) {
            receivableUnitItemService.settleIfPossible(item)
        }
    }
}
