package com.asaas.service.blockedcustomerurl

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class BlockedCustomerUrlCacheService {

    @SuppressWarnings("UnusedMethodParameter")
    @CachePut(value = "BlockedCustomerUrl:byCustomerAndControllerAndAction", key = "(#customerId + ':' + #controller + ':' + #action).toString()")
    public Date save(Long customerId, String controller, String action, Date releaseDate) {
        return releaseDate
    }

    @SuppressWarnings("UnusedMethodParameter")
    @Cacheable(value = "BlockedCustomerUrl:byCustomerAndControllerAndAction", key = "(#customerId + ':' + #controller + ':' + #action).toString()")
    public Date getReleaseDateIfExists(Long customerId, String controller, String action) {
        return null
    }
}
