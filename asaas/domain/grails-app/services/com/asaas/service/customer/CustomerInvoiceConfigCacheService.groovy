package com.asaas.service.customer

import com.asaas.customer.CustomerInvoiceConfigCacheVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerInvoiceConfigCacheService {

    static final String CUSTOMER_INVOICE_CONFIG_BY_CUSTOMER_ID_CACHE_KEY = "CustomerInvoiceConfig:byCustomerId"

    @Cacheable(value = CustomerInvoiceConfigCacheService.CUSTOMER_INVOICE_CONFIG_BY_CUSTOMER_ID_CACHE_KEY)
    public CustomerInvoiceConfigCacheVO byCustomerId(Long customerId) {
        Customer customer = Customer.get(customerId)

        CustomerInvoiceConfig customerInvoiceConfig = customer.getInvoiceConfig()
        return CustomerInvoiceConfigCacheVO.build(customerInvoiceConfig)
    }

    @SuppressWarnings
    @CacheEvict(value = CustomerInvoiceConfigCacheService.CUSTOMER_INVOICE_CONFIG_BY_CUSTOMER_ID_CACHE_KEY)
    public void evictbyCustomerId(Long customerId) {}

}
