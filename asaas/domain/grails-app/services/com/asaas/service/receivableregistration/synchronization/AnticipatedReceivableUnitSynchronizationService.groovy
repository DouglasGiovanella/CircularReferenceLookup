package com.asaas.service.receivableregistration.synchronization

import com.asaas.domain.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueue
import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.integration.cerc.api.CercResponseAdapter
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AnticipatedReceivableUnitSynchronizationService {

    def anticipatedReceivableUnitService
    def cercManagerService
    def receivableRegistrationSynchronizationEventQueueService

    public void processPendingEventList() {
        if (!cercManagerService.isApiAvailable()) return

        final Integer maxItemsPerThread = 50
        final Integer maxItemsPerList = 300
        List<Long> synchronizationEventIdList = receivableRegistrationSynchronizationEventQueueService.listPendingEventsId(ReceivableRegistrationSynchronizationEventQueueName.ANTICIPATED_RECEIVABLE_UNIT, maxItemsPerList)
        ThreadUtils.processWithThreadsOnDemand(synchronizationEventIdList, maxItemsPerThread, { List<Long> eventIdList ->
            for (Long synchronizationEventId : eventIdList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    processEvent(ReceivableRegistrationSynchronizationEventQueue.get(synchronizationEventId))
                }, [logErrorMessage: "AnticipatedReceivableUnitSynchronizationService.processPendingEventList >> Falha ao sincronizar antecipação pós-contratada do evento: [${synchronizationEventId}]",
                    onError: { hasError = true }])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationSynchronizationEventQueueService.updateStatusAsError(synchronizationEventId)
                        anticipatedReceivableUnitService.updateStatusAsError(ReceivableRegistrationSynchronizationEventQueue.read(synchronizationEventId).objectId)
                    }, [logErrorMessage: "AnticipatedReceivableUnitSynchronizationService.processPendingEventList >>  Falha ao atualizar o status para ERROR, evento: [${synchronizationEventId}]"])
                }
            }
        })
    }

    public void processPendingFinishAnticipatedReceivableUnit() {
        if (!cercManagerService.isApiAvailable()) return

        final Integer maxItemsPerThread = 50
        final Integer maxItemsPerList = 300
        List<Long> synchronizationEventIdList = receivableRegistrationSynchronizationEventQueueService.listPendingEventsId(ReceivableRegistrationSynchronizationEventQueueName.FINISH_ANTICIPATED_RECEIVABLE_UNIT, maxItemsPerList)
        ThreadUtils.processWithThreadsOnDemand(synchronizationEventIdList, maxItemsPerThread, { List<Long> eventIdList ->
            for (Long synchronizationEventId : eventIdList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    processEvent(ReceivableRegistrationSynchronizationEventQueue.get(synchronizationEventId))
                }, [logErrorMessage: "AnticipatedReceivableUnitSynchronizationService.processPendingFinishAnticipatedReceivableUnit >> Falha ao sincronizar antecipação pós-contratada do evento: [${synchronizationEventId}]",
                    onError: { hasError = true }])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationSynchronizationEventQueueService.updateStatusAsError(synchronizationEventId)
                        anticipatedReceivableUnitService.updateStatusAsError(ReceivableRegistrationSynchronizationEventQueue.read(synchronizationEventId).objectId)
                    }, [logErrorMessage: "AnticipatedReceivableUnitSynchronizationService.processPendingFinishAnticipatedReceivableUnit >>  Falha ao atualizar o status para ERROR, evento: [${synchronizationEventId}]"])
                }
            }
        })
    }

    private void processEvent(ReceivableRegistrationSynchronizationEventQueue synchronizationEvent) {
        AnticipatedReceivableUnit anticipatedReceivableUnit = AnticipatedReceivableUnit.get(synchronizationEvent.objectId)
        CercResponseAdapter syncResponse = cercManagerService.syncAnticipatedReceivableUnit(anticipatedReceivableUnit)

        receivableRegistrationSynchronizationEventQueueService.handleSyncResponse(synchronizationEvent, syncResponse)
        if (synchronizationEvent.status.isAwaitingNextAttempt()) return

        receivableRegistrationSynchronizationEventQueueService.updateStatusAsDone(synchronizationEvent.id)

        switch (anticipatedReceivableUnit.operationType) {
            case CercOperationType.CREATE:
            case CercOperationType.UPDATE:
                anticipatedReceivableUnitService.handleRegistration(anticipatedReceivableUnit)
                break
            case CercOperationType.INACTIVATE:
                AsaasLogger.info("AnticipatedReceivableUnitSynchronizationService.processEvent -> Inativando pós-contratada: [${anticipatedReceivableUnit.id}]")
                break
            case CercOperationType.FINISH:
                anticipatedReceivableUnitService.updateStatusAsAnticipated(anticipatedReceivableUnit)
                break
            default:
                throw new RuntimeException("Operação não suportada: [${anticipatedReceivableUnit.operationType}]")
        }
    }
}
