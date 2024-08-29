package com.asaas.service.customercommission

import com.asaas.domain.customer.Customer
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.salespartner.repository.SalesPartnerCustomerRepository

import grails.compiler.GrailsCompileStatic
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionCacheService {

    @Cacheable(value = "CustomerCommission:getCommissionedCustomerId", key="#customer.id")
    public Long getCommissionedCustomerId(Customer customer) {
        if (customer.accountOwner) {
            return customer.accountOwner.id
        } else {
            SalesPartner salesPartner = SalesPartnerCustomerRepository.query([customerId: customer.id]).column("salesPartner").get()
            return salesPartner?.partner?.id
        }
    }

    @CacheEvict(value = "CustomerCommission:getCommissionedCustomerId", key="#customer.id")
    public void evictGetCommissionedCustomerId(Customer customer) { }
}
