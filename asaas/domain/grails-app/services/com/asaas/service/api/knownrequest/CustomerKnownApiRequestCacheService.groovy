package com.asaas.service.api.knownrequest

import com.asaas.domain.api.knownrequest.CustomerKnownApiRequestLocationWhitelist
import com.asaas.domain.api.knownrequest.CustomerKnownApiRequestOrigin
import com.asaas.domain.customer.Customer
import com.asaas.service.api.ApiBaseService
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerKnownApiRequestCacheService extends ApiBaseService {

    @Cacheable(value = "CustomerKnownApiRequest:listCustomerKnownLocations", key="#customer.id")
    public List<Map> listCustomerKnownLocations(Customer customer) {
        List<Map> customerLocationList = CustomerKnownApiRequestLocationWhitelist.query([columnList: ["country", "city"], customer: customer, readOnly: true]).list()
        if (customerLocationList) return customerLocationList

        if (customer.accountOwner) {
            customerLocationList = CustomerKnownApiRequestLocationWhitelist.query([columnList: ["country", "city"], customer: customer.accountOwner, readOnly: true]).list()
            if (customerLocationList) return customerLocationList
        }

        return []
    }

    @Cacheable(value = "CustomerKnownApiRequest:listCustomerKnownOrigins", key="#customer.id")
    public List<Map> listCustomerKnownOrigins(Customer customer) {
        List<Map> customerOriginList = CustomerKnownApiRequestOrigin.query([columnList: ["remoteIp", "userAgent"], customer: customer, readOnly: true]).list()
        if (customerOriginList) return customerOriginList

        if (customer.accountOwner) {
            customerOriginList = CustomerKnownApiRequestOrigin.query([columnList: ["remoteIp", "userAgent"], customer: customer.accountOwner, readOnly: true]).list()
            if (customerOriginList) return customerOriginList
        }

        return []
    }

    @CacheEvict(value = "CustomerKnownApiRequest:listCustomerKnownOrigins", key="#customer.id")
    public void evictCustomerOrigin(Customer customer) { }

    @CacheEvict(value = "CustomerKnownApiRequest:listCustomerKnownLocations", key="#customer.id")
    public void evictCustomerLocation(Customer customer) { }
}
