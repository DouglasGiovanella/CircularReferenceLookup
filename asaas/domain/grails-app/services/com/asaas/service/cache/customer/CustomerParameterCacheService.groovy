package com.asaas.service.cache.customer

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerParameterCacheService {

    def grailsCacheManager

    @Cacheable(value = "CustomerParameter:getValue", key = "(#customerId + ':' + #name).toString()")
    public Boolean getValue(Long customerId, CustomerParameterName name) {
        return CustomerParameter.queryValue([customerId: customerId, name: name]).get().asBoolean()
    }

    @Cacheable(value = "CustomerParameter:getNumericValue", key = "(#customerId + ':' + #name).toString()")
    public BigDecimal getNumericValue(Long customerId, CustomerParameterName name) {
        return CustomerParameter.queryNumericValue([customerId: customerId, name: name]).get()
    }

    @Cacheable(value = "CustomerParameter:getStringValue", key = "(#customerId + ':' + #name).toString()")
    public String getStringValue(Long customerId, CustomerParameterName name) {
        return CustomerParameter.queryStringValue([customerId: customerId, name: name]).get()
    }

    @Cacheable(value = "CustomerParameter:getNumericValue", key = "(#customerId + ':' + #name).toString()")
    public BigDecimal getNumericValueWithDefaultValue(Long customerId, CustomerParameterName name, BigDecimal defaultValue) {
        return CustomerParameter.queryNumericValue([customerId: customerId, name: name]).get() ?: defaultValue
    }

    public void evict(Customer customer, CustomerParameterName name) {
        String cacheName = getCacheNameByParameterNameType(name)
        if (!cacheName) return

        final String cacheKey = "${customer.id}:${name}"
        grailsCacheManager.getCache(cacheName).evict(cacheKey)
    }

    private String getCacheNameByParameterNameType(CustomerParameterName name) {
        final String cacheNamePrefix = "CustomerParameter:"

        if (name.valueType == Boolean) return cacheNamePrefix + "getValue"
        if (name.valueType == String) return cacheNamePrefix + "getStringValue"
        if (name.valueType == BigDecimal) return cacheNamePrefix + "getNumericValue"

        return null
    }
}
