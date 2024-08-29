package com.asaas.service.freepayment

import com.asaas.domain.freepaymentconfig.FreePaymentConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class FreePaymentConfigCacheService {

    private static final String FREE_PAYMENTS_AMOUNT_BY_CUSTOMER_ID = "FreePaymentConfig:getFreePaymentsAmount"

    @Cacheable(value = FreePaymentConfigCacheService.FREE_PAYMENTS_AMOUNT_BY_CUSTOMER_ID)
    public Integer getFreePaymentsAmount(Long customerId) {
        Integer freePaymentsAmount = FreePaymentConfig.query([column: "freePaymentsAmount", customerId: customerId]).get()
        return freePaymentsAmount ?: 0
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = FreePaymentConfigCacheService.FREE_PAYMENTS_AMOUNT_BY_CUSTOMER_ID)
    public void evict(Long customerId) { }
}
