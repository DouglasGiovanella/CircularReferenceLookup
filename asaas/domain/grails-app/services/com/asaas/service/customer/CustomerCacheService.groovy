package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerCacheService {

    private static final String IS_ACCOUNT_OWNER_WITH_CHILD_ACCOUNT_CACHE_NAME = "C:iAOWCA"

    @Cacheable(value = "Customer:findDateCreated", key = "#customerId")
    public Date findDateCreated(Long customerId) {
        Customer customer = Customer.findById(customerId)
        return customer.dateCreated
    }

    @Cacheable(value = CustomerCacheService.IS_ACCOUNT_OWNER_WITH_CHILD_ACCOUNT_CACHE_NAME, key = "#customerId")
    public Boolean isAccountOwnerWithChildAccount(Long customerId) {
        Boolean hasChildAccount = Customer.query([exists: true, accountOwnerId: customerId]).get().asBoolean()

        return hasChildAccount
    }

    @CacheEvict(value = CustomerCacheService.IS_ACCOUNT_OWNER_WITH_CHILD_ACCOUNT_CACHE_NAME, key = "#customerId")
    public void evictIsAccountOwnerWithChildAccount(Long customerId) { }
}
