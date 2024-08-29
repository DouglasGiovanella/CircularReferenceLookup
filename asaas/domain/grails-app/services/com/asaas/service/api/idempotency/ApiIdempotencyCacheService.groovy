package com.asaas.service.api.idempotency

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ApiIdempotencyCacheService {

    def grailsCacheManager

    @SuppressWarnings("UnusedMethodParameter")
    @CachePut(value = "ApiIdempotencyCacheService:byCustomerAndIdempotencyHash", key = "#customerId + ':' + #idempotencyHash")
    public String save(Long customerId, String idempotencyHash) {
        return idempotencyHash
    }

    @SuppressWarnings("UnusedMethodParameter")
    @Cacheable(value = "ApiIdempotencyCacheService:byCustomerAndIdempotencyHash", key = "#customerId + ':' + #idempotencyHash")
    public String get(Long customerId, String idempotencyHash) {
        return null
    }

    public void evict(Long customerId, String idempotencyHash) {
        grailsCacheManager.getCache("ApiIdempotencyCacheService:byCustomerAndIdempotencyHash").evict(customerId + ":" + idempotencyHash)
    }
}
