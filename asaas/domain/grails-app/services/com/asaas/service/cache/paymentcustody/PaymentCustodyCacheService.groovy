package com.asaas.service.cache.paymentcustody

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.utils.Utils
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class PaymentCustodyCacheService {

    def grailsCacheManager

    @Cacheable(value = "PaymentCustody:getCustodyDaysForCustomer")
    public Integer getCustodyDaysForCustomer(Long customerId) {
        Customer customer = Customer.read(customerId)

        Integer custodyDays = 0
        if (customer.accountOwner) custodyDays = Utils.toInteger(CustomerParameter.getNumericValue(customer.accountOwner, CustomerParameterName.DAYS_TO_EXPIRE_PAYMENT_CUSTODY))

        return custodyDays
    }

    public void evictGetCustodyDaysForCustomer(Long customerId) {
        final String cacheName = "PaymentCustody:getCustodyDaysForCustomer"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }
}
