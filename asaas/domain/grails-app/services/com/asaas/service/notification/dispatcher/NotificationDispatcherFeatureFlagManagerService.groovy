package com.asaas.service.notification.dispatcher

import com.asaas.exception.BusinessException
import com.asaas.integration.notificationdispatcher.NotificationDispatcherManager
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.dto.NotificationDispatcherToggleFeatureFlagRequestDTO
import com.asaas.utils.Utils

class NotificationDispatcherFeatureFlagManagerService {

    public Map toggleFeatureFlag(String flagName, Boolean enabled) {
        NotificationDispatcherManager notificationDispatcherManager = new NotificationDispatcherManager()
        NotificationDispatcherToggleFeatureFlagRequestDTO toggleFeatureFlagRequest = new NotificationDispatcherToggleFeatureFlagRequestDTO(flagName, enabled)
        notificationDispatcherManager.post("/featureFlag/toggle", toggleFeatureFlagRequest.properties)

        if (notificationDispatcherManager.isSuccessful()) return [success: true]
        if (notificationDispatcherManager.isBadRequest()) throw new BusinessException(notificationDispatcherManager.getErrorMessage())

        AsaasLogger.error("${this.class.simpleName}.toggleFeatureFlag >> Erro ao atualizar featureFlag. [featureFlag: ${flagName} status: ${notificationDispatcherManager.getStatusCode()}, errorMessage: ${notificationDispatcherManager.getErrorMessage()}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map isFeatureFlagEnabled(String flagName) {
        NotificationDispatcherManager notificationDispatcherManager = new NotificationDispatcherManager()
        notificationDispatcherManager.get("/featureFlag/enabled", [flagName: flagName])

        if (notificationDispatcherManager.isSuccessful()) return notificationDispatcherManager.responseBody
        if (notificationDispatcherManager.isBadRequest()) throw new BusinessException(notificationDispatcherManager.getErrorMessage())

        AsaasLogger.error("${this.class.simpleName}.toggleFeatureFlag >> Erro ao consultar featureFlag. [featureFlag: ${flagName} status: ${notificationDispatcherManager.getStatusCode()}, errorMessage: ${notificationDispatcherManager.getErrorMessage()}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }
}
