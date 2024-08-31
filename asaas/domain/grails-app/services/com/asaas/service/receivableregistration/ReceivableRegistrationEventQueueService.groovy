package com.asaas.service.receivableregistration

import com.asaas.domain.receivableregistration.ReceivableRegistrationEventQueue
import com.asaas.namedqueries.SqlOrder
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueStatus
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import groovy.json.JsonOutput

@Transactional
class ReceivableRegistrationEventQueueService {

    def dataSource

    public void save(ReceivableRegistrationEventQueueType type, Map eventData, String groupId) {
        ReceivableRegistrationEventQueue eventQueue = buildObject(type, eventData, groupId)
        eventQueue.save(failOnError: true)
    }

    public void saveInBatch(List<ReceivableRegistrationEventQueue> receivableRegistrationEventQueueList) {
        if (!receivableRegistrationEventQueueList) return

        List<Map> eventQueueToInsert = receivableRegistrationEventQueueList.collect { ReceivableRegistrationEventQueue eventQueue ->
            eventQueue.discard()
            return [
                "version": "0",
                "date_created": new Date(),
                "deleted": 0,
                "event_data": eventQueue.eventData,
                "event_data_hash": eventQueue.eventDataHash,
                "group_id": eventQueue.groupId,
                "last_updated": new Date(),
                "priority": eventQueue.priority,
                "status": eventQueue.status.toString(),
                "type": eventQueue.type.toString()
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "receivable_registration_event_queue", eventQueueToInsert)
    }

    public ReceivableRegistrationEventQueue buildObject(ReceivableRegistrationEventQueueType type, Map eventData, String groupId) {
        String eventDataJson = JsonOutput?.toJson(eventData)
        String eventDataHash = eventDataJson?.encodeAsMD5()

        ReceivableRegistrationEventQueue validatedEventQueue = validateSave(eventData, eventDataHash, type)
        if (validatedEventQueue.hasErrors()) throw new ValidationException("Falha ao criar evento do tipo [${type}] com os dados ${eventData}", validatedEventQueue.errors)

        ReceivableRegistrationEventQueue eventQueue = new ReceivableRegistrationEventQueue()
        eventQueue.status = ReceivableRegistrationEventQueueStatus.PENDING
        eventQueue.priority = ReceivableRegistrationEventQueue.DEFAULT_PRIORITY
        eventQueue.type = type
        eventQueue.groupId = groupId
        eventQueue.eventData = eventDataJson
        eventQueue.eventDataHash = eventDataHash

        return eventQueue
    }

    public void saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType type, Map eventData, String groupId) {
        Boolean hasEventPendingWithSameGroupId = ReceivableRegistrationEventQueue.query([exists: true, groupId: groupId, status: ReceivableRegistrationEventQueueStatus.PENDING, type: type]).get().asBoolean()
        if (!hasEventPendingWithSameGroupId) save(type, eventData, groupId)
    }

    public void saveIfHasNoEventPendingWithSameGroupIdAndHash(ReceivableRegistrationEventQueueType type, Map eventData, String groupId) {
        String eventDataJson = JsonOutput.toJson(eventData)
        String eventDataHash = eventDataJson.encodeAsMD5()

        Boolean hasEventPendingWithSameGroupIdAndHash = ReceivableRegistrationEventQueue.query([exists: true, groupId: groupId, eventDataHash: eventDataHash, status: ReceivableRegistrationEventQueueStatus.PENDING, type: type]).get().asBoolean()
        if (!hasEventPendingWithSameGroupIdAndHash) save(type, eventData, groupId)
    }

    public List<Map> listPendingEventData(ReceivableRegistrationEventQueueType type, List<String> groupIdList, SqlOrder sqlOrder, Integer max) {
        Map queryParams = [
            type: type
        ]

        if (groupIdList) queryParams."groupId[in]" = groupIdList
        if (sqlOrder) queryParams."sqlOrder" = sqlOrder

        List<ReceivableRegistrationEventQueue> eventQueueListList = ReceivableRegistrationEventQueue.oldestPending(queryParams).list(max: max)
        return eventQueueListList.collect { it.getDataAsMap() }
    }

    public void delete(Long eventQueueId) {
        ReceivableRegistrationEventQueue eventQueue = ReceivableRegistrationEventQueue.get(eventQueueId)
        eventQueue.delete(failOnError: true)
    }

    public void deleteBatch(List<Long> receivableRegistrationEventQueueForDeleteIdList) {
        if (!receivableRegistrationEventQueueForDeleteIdList) return
        ReceivableRegistrationEventQueue.executeUpdate("DELETE FROM ReceivableRegistrationEventQueue WHERE id IN (:receivableRegistrationEventQueueForDeleteIdList)", [receivableRegistrationEventQueueForDeleteIdList: receivableRegistrationEventQueueForDeleteIdList])
    }

    public void updateBatchStatusAsProcessing(List<Long> eventIdList) {
        if (!eventIdList) return

        Utils.withNewTransactionAndRollbackOnError({
            ReceivableRegistrationEventQueue.executeUpdate("UPDATE ReceivableRegistrationEventQueue SET status = :status, version = version + 1, lastUpdated = :now WHERE id IN (:idList)", [status: ReceivableRegistrationEventQueueStatus.PROCESSING, now: new Date(), idList: eventIdList])
        },[logErrorMessage: "receivableRegistrationEventQueueService.updateBatchStatusAsProcessing -> Não foi possível atualizar o status para PROCESSING dos eventos [${eventIdList}]"])
    }

    public void updateAsProcessed(Long eventQueueId) {
        ReceivableRegistrationEventQueue eventQueue = ReceivableRegistrationEventQueue.get(eventQueueId)
        eventQueue.status = ReceivableRegistrationEventQueueStatus.PROCESSED
        eventQueue.save(failOnError: true)
    }

    public void setAsError(Long eventQueueId) {
        ReceivableRegistrationEventQueue eventQueue = ReceivableRegistrationEventQueue.get(eventQueueId)
        eventQueue.status = ReceivableRegistrationEventQueueStatus.ERROR
        eventQueue.save(failOnError: true)
    }

    public void updateAsPending(Long eventQueueId) {
        ReceivableRegistrationEventQueue eventQueue = ReceivableRegistrationEventQueue.get(eventQueueId)
        eventQueue.status = ReceivableRegistrationEventQueueStatus.PENDING
        eventQueue.save(failOnError: true)
    }

    private ReceivableRegistrationEventQueue validateSave(Map eventData, String eventDataHash, ReceivableRegistrationEventQueueType eventQueueType) {
        ReceivableRegistrationEventQueue validatedEventQueue = new ReceivableRegistrationEventQueue()

        if (!eventData) {
            DomainUtils.addError(validatedEventQueue, "O conteúdo do evento não pode ser vazio")
            return validatedEventQueue
        }

        if (!eventQueueType.allowDuplicated) {
            Boolean hasReceivableRegistrationEventQueuePendingWithSameParameters = ReceivableRegistrationEventQueue.query([exists: true, eventDataHash: eventDataHash, status: ReceivableRegistrationEventQueueStatus.PENDING, type: eventQueueType]).get().asBoolean()
            if (hasReceivableRegistrationEventQueuePendingWithSameParameters) {
                DomainUtils.addError(validatedEventQueue, "Já existe um evento pendente com o mesmo parâmetro e tipo")
                return validatedEventQueue
            }
        }

        return validatedEventQueue
    }
}
