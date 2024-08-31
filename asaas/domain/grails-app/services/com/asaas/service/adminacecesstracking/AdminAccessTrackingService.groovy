package com.asaas.service.adminacecesstracking

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.integration.sauron.adapter.adminaccesstracking.AdminAccessEventAdapter
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class AdminAccessTrackingService {

    def adminAccessTrackingManagerService
    def asyncActionService

    public void save(Map params, String resourceId, Long customerId) {
        Long userId = UserUtils.getCurrentUserId(null)
        save(params, userId, resourceId, customerId)
    }

    public void save(Map params, Long userId, String resourceId, Long customerId) {
        final String defaultType = "ADMIN_ACCESS"

        Map eventParams = [:]

        eventParams.userId = userId
        eventParams.customerId = customerId
        eventParams.type = defaultType

        eventParams.eventData = [:]
        eventParams.eventData.controllerName = params.controller
        eventParams.eventData.actionName = params.action
        eventParams.eventData.resourceId = resourceId

        saveAsyncAction(eventParams)
    }

    public void processSaveAdminAccessEvent() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_ADMIN_ACCESS_EVENT, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                AdminAccessEventAdapter adminAccessEventAdapter = AdminAccessEventAdapter.fromAsyncActionData(asyncActionData)
                adminAccessTrackingManagerService.save(adminAccessEventAdapter)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                    ignoreStackTrace: true,
                    onError: { Exception exception ->
                        AsaasLogger.error("AdminAccessTrackingService.processSaveAdminAccessEvent >> AsyncActionID: [${asyncActionData.asyncActionId}]", exception)
                        asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                    }
            ])
        }
    }

    private void saveAsyncAction(Map eventMap) {
        Integer actionDataMaxSize = AsyncAction.constraints.actionData.getMaxSize()

        try {
            String actionDataJson = JsonOutput.toJson(eventMap)

            if (actionDataJson.length() > actionDataMaxSize) {
                String errorMessage = "O campo ActionData não pode conter mais que ${actionDataMaxSize} caracteres."
                AsaasLogger.warn("AdminAccessTrackingService.saveAsyncAction >> ${errorMessage}.")
                return
            }

            if (asyncActionService.hasAsyncActionPendingWithSameParameters(eventMap, AsyncActionType.SAVE_ADMIN_ACCESS_EVENT)) return

            asyncActionService.save(AsyncActionType.SAVE_ADMIN_ACCESS_EVENT, eventMap)
        } catch (Exception exception) {
            AsaasLogger.error("AdminAccessTrackingService.saveAsyncAction >> Erro ao salvar AsyncAction.", exception)
        }
    }
}
