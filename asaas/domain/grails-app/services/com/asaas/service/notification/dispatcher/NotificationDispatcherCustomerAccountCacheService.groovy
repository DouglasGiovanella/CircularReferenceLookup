package com.asaas.service.notification.dispatcher

import com.asaas.domain.notification.NotificationDispatcherCustomerAccount
import com.asaas.notification.dispatcher.cache.NotificationDispatcherCustomerAccountCacheVO

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class NotificationDispatcherCustomerAccountCacheService {

    static final String BY_CUSTOMER_ACCOUNT_ID_CACHE_KEY = "NotificationDispatcherCustomerAccount:byCustomerAccountId"

    @Cacheable(value = NotificationDispatcherCustomerAccountCacheService.BY_CUSTOMER_ACCOUNT_ID_CACHE_KEY)
    public NotificationDispatcherCustomerAccountCacheVO byCustomerAccountId(Long customerAccountId) {
        NotificationDispatcherCustomerAccount notificationDispatcherCustomerAccount = NotificationDispatcherCustomerAccount.query([customerAccountId: customerAccountId]).get()
        if (!notificationDispatcherCustomerAccount) return null
        return NotificationDispatcherCustomerAccountCacheVO.build(notificationDispatcherCustomerAccount)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = NotificationDispatcherCustomerAccountCacheService.BY_CUSTOMER_ACCOUNT_ID_CACHE_KEY)
    public void evictByCustomerAccountId(Long customerAccountId) {}

}
