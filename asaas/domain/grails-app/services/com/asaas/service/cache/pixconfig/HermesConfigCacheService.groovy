package com.asaas.service.cache.pixconfig

import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.config.HermesConfigAdapter

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class HermesConfigCacheService {

    def hermesConfigManagerService

    @Cacheable(value = "HermesConfigAdapter:getInstance", key = "'instance'")
    public HermesConfigAdapter getInstance() {
        return requestHermesConfig()
    }

    @CachePut(value = "HermesConfigAdapter:getInstance", key = "'instance'")
    public HermesConfigAdapter updateInstance() {
        return requestHermesConfig()
    }

    private HermesConfigAdapter requestHermesConfig() {
        try {
            return hermesConfigManagerService.get()
        } catch (Exception exception) {
            AsaasLogger.error("HermesConfigCacheService.requestHermesConfig >> Não foi possível buscar as configurações do Hermes", exception)
            return new HermesConfigAdapter()
        }
    }

}
