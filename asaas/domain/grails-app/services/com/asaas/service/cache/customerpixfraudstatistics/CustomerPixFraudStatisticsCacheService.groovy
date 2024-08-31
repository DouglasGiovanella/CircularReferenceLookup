package com.asaas.service.cache.customerpixfraudstatistics

import com.asaas.domain.customerpixfraudstatistics.CustomerPixFraudStatistics
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerPixFraudStatisticsCacheService {

    def grailsCacheManager

    @Cacheable(value = "CustomerPixFraudStatistics:getTotalAccountFraudConfirmedInLastOneYear")
    public Integer getTotalAccountFraudConfirmedInLastOneYear(Long customerId) {
        return CustomerPixFraudStatistics.query([column: "totalAccountFraudConfirmedInLastOneYear", customerId: customerId]).get()
    }

    public void evictTotalAccountFraudConfirmedInLastOneYear(Long customerId) {
        final String cacheName = "CustomerPixFraudStatistics:getTotalAccountFraudConfirmedInLastOneYear"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }
}
