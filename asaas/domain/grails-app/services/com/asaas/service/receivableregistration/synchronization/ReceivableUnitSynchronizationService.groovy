package com.asaas.service.receivableregistration.synchronization

import com.asaas.domain.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueue
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.adapter.receivableunit.ReceivableUnitAdapter
import com.asaas.integration.cerc.api.CercResponseAdapter
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.webhook.CercReceivableUnitErrorReason
import com.asaas.integration.cerc.parser.CercParser
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueStatus
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableUnitSynchronizationService {

    def anticipatedReceivableUnitService
    def asyncActionService
    def cercManagerService
    def grailsApplication
    def receivableRegistrationSynchronizationEventQueueService
    def receivableUnitService

    public void processPendingEventList() {
        if (!cercManagerService.isApiAvailable()) return

        final Integer maxItemsPerThread = 50
        final Integer maxItemsPerList = 600
        List<Long> synchronizationEventIdList = receivableRegistrationSynchronizationEventQueueService.listPendingEventsId(ReceivableRegistrationSynchronizationEventQueueName.RECEIVABLE_UNIT, maxItemsPerList)
        ThreadUtils.processWithThreadsOnDemand(synchronizationEventIdList, maxItemsPerThread, { List<Long> eventIdList ->
            for (Long synchronizationEventId : eventIdList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    processEvent(ReceivableRegistrationSynchronizationEventQueue.get(synchronizationEventId))
                }, [logErrorMessage: "ReceivableUnitSynchronizationService.processPendingEventList >> Falha ao sincronizar unidade de recebivel do evento: [${synchronizationEventId}]",
                    onError: { hasError = true }])

                if (hasError) {
                    Utils.withNewTransactionAndRollbackOnError({
                        receivableRegistrationSynchronizationEventQueueService.updateStatusAsError(synchronizationEventId)
                        receivableUnitService.updateStatusAsError(ReceivableRegistrationSynchronizationEventQueue.get(synchronizationEventId).objectId)
                    }, [logErrorMessage: "ReceivableUnitSynchronizationService.processPendingEventList >> Falha ao atualizar o status para ERROR, evento: [${synchronizationEventId}]"])
                }
            }
        })
    }

    public void processAsyncResponse(ReceivableUnitAdapter receivableUnitAdapter, Long webhookRequestId) {
        if (receivableUnitAdapter.externalIdentifier.startsWith(grailsApplication.config.asaas.cnpj.substring(1))) {
            receivableUnitAdapter.externalIdentifier = receivableUnitAdapter.externalIdentifier.substring(receivableUnitAdapter.externalIdentifier.lastIndexOf("-") + 1)
        }

        ReceivableUnit receivableUnit = ReceivableUnit.get(Long.valueOf(receivableUnitAdapter.externalIdentifier))
        if (!receivableUnit) throw new RuntimeException("ReceivableUnit não encontrado para o identificador ${receivableUnitAdapter.externalIdentifier}")

        ReceivableRegistrationSynchronizationEventQueue synchronizationEvent = findAwaitingResponseEvent(receivableUnit.id, receivableUnitAdapter.protocol)
        if (!synchronizationEvent) {
            AsaasLogger.warn("Não encontrado evento de sincronização aguardando resposta para os dados: UR [${receivableUnit.id}], protocolo [${receivableUnitAdapter.protocol}], webhookId: ${webhookRequestId}]")
            return
        }

        synchronizationEvent.webhookRequestId = webhookRequestId

        if (receivableUnitAdapter.errorList) {
            handleResponseErrorList(receivableUnit, synchronizationEvent, receivableUnitAdapter.errorList)

            if (synchronizationEvent.status.isError() || synchronizationEvent.status.isAwaitingNextAttempt()) return
        }

        sendAnticipatedReceivableUnitsToFinish(receivableUnit.id)

        if (receivableUnit.operationType.isCreate()) receivableUnitService.updateOperationTypeAsUpdate(receivableUnit)
        receivableRegistrationSynchronizationEventQueueService.updateStatusAsDone(synchronizationEvent.id)
    }

    private void handleResponseErrorList(ReceivableUnit receivableUnit, ReceivableRegistrationSynchronizationEventQueue synchronizationEvent, List<Map> errorList) {
        if (CercParser.shouldIgnoreErrorResponse([isError: true, errorList: errorList])) return

        List<CercReceivableUnitErrorReason> errorReasonList = []
        for (Map error : errorList) {
            CercReceivableUnitErrorReason reason = CercReceivableUnitErrorReason.findByCode(error.codigo.toString())
            errorReasonList.add(reason)
        }

        if (errorReasonList.contains(CercReceivableUnitErrorReason.COMPANY_NOT_REGISTERED)) {
            asyncActionService.saveCreateOrUpdateCercCompany(receivableUnit.customerCpfCnpj)
            receivableUnitService.updateStatusAsAwaitingCompanyActivate(receivableUnit)
            return
        }

        if (!receivableRegistrationSynchronizationEventQueueService.hasReachedMaxAttempts(synchronizationEvent)) {
            receivableRegistrationSynchronizationEventQueueService.updateStatusAsAwaitingNextAttempt(synchronizationEvent.id)
            return
        }

        String errorMessage = CercParser.parseErrorList(errorList)
        AsaasLogger.error("ReceivableUnitSynchronizationService.handleResponseErrorList >> Foram encontrados erros na sincronização da UR [Evento: [${synchronizationEvent.id}], errorList: [${errorMessage}]]")
        receivableUnitService.updateStatusAsError(receivableUnit.id)
        receivableRegistrationSynchronizationEventQueueService.updateStatusAsError(synchronizationEvent.id)
    }

    private void processEvent(ReceivableRegistrationSynchronizationEventQueue synchronizationEvent) {
        ReceivableUnit receivableUnit = ReceivableUnit.get(synchronizationEvent.objectId)
        CercResponseAdapter syncResponse = cercManagerService.syncReceivableUnit(receivableUnit)

        receivableRegistrationSynchronizationEventQueueService.handleSyncResponse(synchronizationEvent, syncResponse)
        if (synchronizationEvent.status.isAwaitingNextAttempt()) return

        receivableRegistrationSynchronizationEventQueueService.updateStatusAsAwaitingResponse(synchronizationEvent.id)
        if (CercOperationType.listActiveTypes().contains(receivableUnit.operationType)) receivableUnitService.updateStatusAsProcessed(receivableUnit)
    }

    private ReceivableRegistrationSynchronizationEventQueue findAwaitingResponseEvent(Long receivableUnitId, String protocol) {
        Map search = [:]
        search.objectId = receivableUnitId
        search.protocol = protocol
        search.name = ReceivableRegistrationSynchronizationEventQueueName.RECEIVABLE_UNIT
        search.status = ReceivableRegistrationSynchronizationEventQueueStatus.AWAITING_RESPONSE

        return ReceivableRegistrationSynchronizationEventQueue.query(search).get()
    }

    private void sendAnticipatedReceivableUnitsToFinish(Long receivableUnitId) {
        Map search = [:]
        search.column = "anticipatedReceivableUnit.id"
        search.receivableUnitId = receivableUnitId
        search.status = ReceivableUnitItemStatus.SETTLED
        search.disableSort = true
        search."anticipatedReceivableUnit[isNotNull]" = true
        search.anticipatedReceivableUnitIsRegistered = true

        List<Long> anticipatedReceivableUnitIdList = ReceivableUnitItem.query(search).list()
        for (Long id : anticipatedReceivableUnitIdList) {
            anticipatedReceivableUnitService.updateOperationTypeAsFinish(id)
            receivableRegistrationSynchronizationEventQueueService.saveIfNecessary(id, ReceivableRegistrationSynchronizationEventQueueName.ANTICIPATED_RECEIVABLE_UNIT)
        }
    }
}
