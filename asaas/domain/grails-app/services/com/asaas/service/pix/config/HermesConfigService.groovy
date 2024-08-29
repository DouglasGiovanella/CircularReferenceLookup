package com.asaas.service.pix.config

import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.config.HermesConfigAdapter
import com.asaas.pix.adapter.config.HermesContextConfigListAdapter
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class HermesConfigService {

    def hermesConfigCacheService

    public HermesContextConfigListAdapter getConfigList() {
        HermesConfigAdapter hermesConfigAdapter = hermesConfigCacheService.getInstance()

        if (shouldUpdateConfig(hermesConfigAdapter)) hermesConfigAdapter = hermesConfigCacheService.updateInstance()

        return hermesConfigAdapter.hermesContextConfigListAdapter
    }

    private Boolean shouldUpdateConfig(HermesConfigAdapter hermesConfigAdapter) {
        Map contextConfigListProperties = hermesConfigAdapter.hermesContextConfigListAdapter.properties
        contextConfigListProperties.remove("class")

        Boolean hasAnyNullPropertyInContextConfigList = contextConfigListProperties.any { String key, Object value ->
            return value.properties.containsValue(null)
        }

        if (!hasAnyNullPropertyInContextConfigList) return false

        Long minimumUpdateIntervalSeconds = TimeUnit.MINUTES.toSeconds(10)
        Long secondsSinceLastUpdate = CustomDateUtils.calculateDifferenceInSeconds(hermesConfigAdapter.lastUpdateDate, new Date())

        if (secondsSinceLastUpdate < minimumUpdateIntervalSeconds) {
            AsaasLogger.warn("HermesConfigService.shouldUpdateConfig >> Tentativa de atualização das configurações antes do tempo permitido [lastUpdateDate: ${hermesConfigAdapter.lastUpdateDate}]")
            return false
        }

        return true
    }

}
