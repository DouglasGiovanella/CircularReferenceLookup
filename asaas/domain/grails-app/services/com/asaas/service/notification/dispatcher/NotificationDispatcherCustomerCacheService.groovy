package com.asaas.service.notification.dispatcher

import com.asaas.domain.notification.NotificationDispatcherCustomer
import com.asaas.notification.dispatcher.cache.NotificationDispatcherCustomerCacheVO

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class NotificationDispatcherCustomerCacheService {

    static final String BY_CUSTOMER_ID_CACHE_KEY = "NotificationDispatcherCustomer:byCustomerId"

    @Cacheable(value = NotificationDispatcherCustomerCacheService.BY_CUSTOMER_ID_CACHE_KEY)
    public NotificationDispatcherCustomerCacheVO byCustomerId(Long customerId) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.query([customerId: customerId]).get()
        if (!notificationDispatcherCustomer) return null
        return NotificationDispatcherCustomerCacheVO.build(notificationDispatcherCustomer)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = NotificationDispatcherCustomerCacheService.BY_CUSTOMER_ID_CACHE_KEY)
    public void evictByCustomerId(Long customerAccountId) {}

}
