package com.asaas.service.asyncaction

import com.asaas.domain.connectedaccount.ConnectedAccountEventAsyncAction
import com.asaas.integration.sauron.adapter.ConnectedAccountEventAdapter
import com.asaas.integration.sauron.enums.ConnectedAccountEvent
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class ConnectedAccountEventAsyncActionService {

    def baseAsyncActionService
    def connectedAccountManagerService
    def receivableAnticipationValidationCacheService

    public void saveIfPossible(Map eventMap) {
        final Integer actionDataMaxSize = 1000

        String actionDataJson = JsonOutput.toJson(eventMap)
        if (actionDataJson.length() > actionDataMaxSize) {
            AsaasLogger.error("ConnectedAccountEventAsyncActionService.saveIfPossible >> O campo ActionData não pode conter mais que 1000 caracteres.")
            return
        }

        if (baseAsyncActionService.hasAsyncActionPendingWithSameParameters(ConnectedAccountEventAsyncAction, eventMap)) return

        baseAsyncActionService.save(new ConnectedAccountEventAsyncAction(), eventMap)
    }

    public void processConnectedAccountEvent() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(ConnectedAccountEventAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        baseAsyncActionService.processListWithNewTransaction(ConnectedAccountEventAsyncAction, asyncActionDataList, { Map asyncActionData ->
            Map eventMap = asyncActionData.findAll { it.key != "asyncActionId" }
            List<ConnectedAccountEventAdapter> eventList = eventMap.collect { parseSaveParams(it.value) }

            connectedAccountManagerService.saveList(eventList)
        })
    }

    private ConnectedAccountEventAdapter parseSaveParams(Map params) {
        Long customerId = params.customerId
        Long userId = params.userId
        ConnectedAccountEvent type = ConnectedAccountEvent.convert(params.type)
        Map eventData = params.eventData

        return new ConnectedAccountEventAdapter(customerId, type, userId, eventData)
    }
}
