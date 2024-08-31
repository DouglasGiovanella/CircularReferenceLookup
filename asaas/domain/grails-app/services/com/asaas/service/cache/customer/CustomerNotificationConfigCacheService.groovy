package com.asaas.service.cache.customer

import com.asaas.cache.customer.CustomerNotificationConfigCacheVO
import com.asaas.domain.customer.CustomerNotificationConfig

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerNotificationConfigCacheService {

    static final String CUSTOMER_NOTIFICATION_CONFIG_BY_CUSTOMER_ID_CACHE_KEY = "CustomerNotificationConfig:getInstance"

    @Cacheable(value = CustomerNotificationConfigCacheService.CUSTOMER_NOTIFICATION_CONFIG_BY_CUSTOMER_ID_CACHE_KEY)
    public CustomerNotificationConfigCacheVO getInstance(Long customerId) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.query([customerId: customerId]).get()
        return CustomerNotificationConfigCacheVO.build(customerNotificationConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = CustomerNotificationConfigCacheService.CUSTOMER_NOTIFICATION_CONFIG_BY_CUSTOMER_ID_CACHE_KEY)
    public void evictGetInstance(Long customerId) {}
}
