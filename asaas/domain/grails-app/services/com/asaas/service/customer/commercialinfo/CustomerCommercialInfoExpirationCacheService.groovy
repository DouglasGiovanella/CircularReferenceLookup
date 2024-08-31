package com.asaas.service.customer.commercialinfo

import com.asaas.domain.customer.commercialinfo.CustomerCommercialInfoExpiration
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerCommercialInfoExpirationCacheService {

    @Cacheable(value = "CustomerCommercialInfoExpiration:isExpired", key = "#customerId")
    public Boolean isExpired(Long customerId) {
        Date scheduledDate = CustomerCommercialInfoExpiration.query([column: "scheduledDate", customerId: customerId]).get()
        if (!scheduledDate) return false

        return scheduledDate <= new Date().clearTime()
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "CustomerCommercialInfoExpiration:isExpired", key="#customerId")
    public void evictIsExpired(Long customerId) {
        return
    }
}
