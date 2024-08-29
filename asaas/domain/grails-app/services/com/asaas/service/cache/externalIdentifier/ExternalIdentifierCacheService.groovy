package com.asaas.service.cache.externalIdentifier

import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.externalidentifier.ExternalApplication

import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ExternalIdentifierCacheService {

    @Cacheable(value = "ExternalIdentifier:getExternalIdentifier", key = "#root.target.generateCacheKey(#customerId, #application)")
    public String getExternalIdentifier(Long customerId, ExternalApplication application) {
        return ExternalIdentifier.query([column: "externalId", customerId: customerId, application: application]).get()
    }

    public String generateCacheKey(Long customerId, ExternalApplication application) {
        return "CUSTOMER:${customerId}:APPLICATION:${application}"
    }
}
