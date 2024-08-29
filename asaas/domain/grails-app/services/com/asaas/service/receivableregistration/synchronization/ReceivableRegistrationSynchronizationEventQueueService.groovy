package com.asaas.service.receivableregistration.synchronization

import com.asaas.domain.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueue
import com.asaas.integration.cerc.api.CercResponseAdapter
import com.asaas.integration.cerc.parser.CercParser
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableRegistrationSynchronizationEventQueueService {

    public void saveIfNecessary(Long objectId, ReceivableRegistrationSynchronizationEventQueueName name) {
        if (hasAwaitingSynchronizationEventWithSameParameters(objectId, name)) return

        ReceivableRegistrationSynchronizationEventQueue synchronizationQueue = new ReceivableRegistrationSynchronizationEventQueue()
        synchronizationQueue.objectId = objectId
        synchronizationQueue.name = name
        synchronizationQueue.status = ReceivableRegistrationSynchronizationEventQueueStatus.PENDING
        synchronizationQueue.save(failOnError: true)
    }

    public void updateStatusAsDone(Long eventId) {
        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = ReceivableRegistrationSynchronizationEventQueue.get(eventId)
        synchronizationEvent.status = ReceivableRegistrationSynchronizationEventQueueStatus.DONE
        synchronizationEvent.save(failOnError: true)
    }

    public void updateStatusAsAwaitingResponse(Long eventId) {
        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = ReceivableRegistrationSynchronizationEventQueue.get(eventId)
        synchronizationEvent.status = ReceivableRegistrationSynchronizationEventQueueStatus.AWAITING_RESPONSE
        synchronizationEvent.save(failOnError: true)
    }

    public void updateStatusAsError(Long eventId) {
        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = ReceivableRegistrationSynchronizationEventQueue.get(eventId)
        synchronizationEvent.status = ReceivableRegistrationSynchronizationEventQueueStatus.ERROR
        synchronizationEvent.save(flush: true, failOnError: true)
    }

    public void processAwaitingNextAttempt() {
        Map search = [:]
        search.column = "id"
        search.status = ReceivableRegistrationSynchronizationEventQueueStatus.AWAITING_NEXT_ATTEMPT
        search."nextAttemptDate[le]" = new Date()
        search.disableSort = true

        final Integer maxItemsPerThread = 50
        List<Long> awaitingNextAttemptSynchronizationEventIdList = ReceivableRegistrationSynchronizationEventQueue.query(search).list(max: 150)
        ThreadUtils.processWithThreadsOnDemand(awaitingNextAttemptSynchronizationEventIdList, maxItemsPerThread, { List<Long> synchronizationIdList ->
            for (Long synchronizationEventId : synchronizationIdList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    updateStatusAsPending(synchronizationEventId)
                }, [logErrorMessage: "ReceivableRegistrationSynchronizationEventQueueService.processAwaitingNextAttempt >> Falha ao atualizar o status para PENDING, evento: [${synchronizationEventId}]",
                    onError: { hasError = true }])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        updateStatusAsError(synchronizationEventId)
                    }, [logErrorMessage: "ReceivableRegistrationSynchronizationEventQueueService.processAwaitingNextAttempt >> Falha ao atualizar o status para ERROR, evento: [${synchronizationEventId}]"])
                }
            }
        })
    }

    public void handleSyncResponse(ReceivableRegistrationSynchronizationEventQueue synchronizationEvent, CercResponseAdapter syncResponse) {
        synchronizationEvent.protocol = syncResponse.protocol
        synchronizationEvent.save(failOnError: true)

        if (syncResponse.status.isSuccess()) return

        if (syncResponse.errorList && ReceivableRegistrationSynchronizationEventQueueName.listAnticipatedReceivableUnit().contains(synchronizationEvent.name)) {
            Boolean shouldIgnoreAnticipatedReceivableUnitErrorList = shouldIgnoreAnticipatedReceivableUnitErrorList(syncResponse.errorList)
            if (shouldIgnoreAnticipatedReceivableUnitErrorList) return
        }

        if (!hasReachedMaxAttempts(synchronizationEvent)) {
            updateStatusAsAwaitingNextAttempt(synchronizationEvent.id)
            return
        }

        String errorMessage = "A requisição não foi bem sucedida. Motivos: "
        if (syncResponse.errorList) errorMessage += CercParser.parseErrorList(syncResponse.errorList)

        throw new RuntimeException(errorMessage)
    }

    public List<Long> listPendingEventsId(ReceivableRegistrationSynchronizationEventQueueName name, Integer maxItemsPerList) {
        Map search = [:]
        search.column = "id"
        search.name = name
        search.status = ReceivableRegistrationSynchronizationEventQueueStatus.PENDING
        search."eventAwaitingResponseForSameObjectId[notExists]" = true
        search.disableSort = true

        return ReceivableRegistrationSynchronizationEventQueue.query(search).list(max: maxItemsPerList)
    }

    public Boolean hasReachedMaxAttempts(ReceivableRegistrationSynchronizationEventQueue synchronizationEvent) {
        return synchronizationEvent.attempts == synchronizationEvent.name.maxAttempts
    }

    public void updateStatusAsAwaitingNextAttempt(Long eventId) {
        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = ReceivableRegistrationSynchronizationEventQueue.get(eventId)
        synchronizationEvent.status = ReceivableRegistrationSynchronizationEventQueueStatus.AWAITING_NEXT_ATTEMPT

        if (hasReachedMaxAttempts(synchronizationEvent)) throw new RuntimeException("Número máximo de tentativas de sincronização atingido.")
        synchronizationEvent.attempts++
        synchronizationEvent.nextAttemptDate = calculateNextAttemptDate(synchronizationEvent)
        synchronizationEvent.save(failOnError: true)
    }

    private Boolean shouldIgnoreAnticipatedReceivableUnitErrorList(List<Map> errorList) {
        if (CercParser.shouldIgnoreErrorResponse([isError: true, errorList: errorList])) return true

        final List<String> errorCodesToIgnoreList = ["103846", "103859"]
        if (errorList.any { errorCodesToIgnoreList.contains(it.codigo) }) return true

        return false
    }

    private Date calculateNextAttemptDate(ReceivableRegistrationSynchronizationEventQueue synchronizationEvent) {
        Date now = new Date()

        final Integer maxMinuteToLimitHourToSynchronize = 0
        final Integer maxSecondsToLimitHourToSynchronize = 0
        Date limitHourToSynchronize = CustomDateUtils.setTime(now, synchronizationEvent.name.limitHourToSynchronize, maxMinuteToLimitHourToSynchronize, maxSecondsToLimitHourToSynchronize)

        final Integer intervalInMinutes = 3
        Integer minutesToNextAttempt = Math.pow(intervalInMinutes, synchronizationEvent.attempts).toInteger()

        Date nextAttemptDate = CustomDateUtils.sumMinutes(now, minutesToNextAttempt)
        if (nextAttemptDate >= limitHourToSynchronize) nextAttemptDate = now

        return nextAttemptDate
    }

    private void updateStatusAsPending(Long eventId) {
        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = ReceivableRegistrationSynchronizationEventQueue.get(eventId)
        synchronizationEvent.status = ReceivableRegistrationSynchronizationEventQueueStatus.PENDING
        synchronizationEvent.save(failOnError: true)
    }

    private Boolean hasAwaitingSynchronizationEventWithSameParameters(Long objectId, ReceivableRegistrationSynchronizationEventQueueName name) {
        Map search = [:]
        search.exists = true
        search.objectId = objectId
        search.statusList = ReceivableRegistrationSynchronizationEventQueueStatus.listAwaitingSynchronization()
        search.name = name

        return ReceivableRegistrationSynchronizationEventQueue.query(search).get().asBoolean()
    }
}
