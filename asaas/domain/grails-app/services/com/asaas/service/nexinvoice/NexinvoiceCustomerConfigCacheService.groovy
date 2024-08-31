package com.asaas.service.nexinvoice

import com.asaas.cache.nexinvoicecustomerconfig.NexinvoiceCustomerConfigCacheVO
import com.asaas.domain.nexinvoice.NexinvoiceCustomerConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable

import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerConfigCacheService {

    private static final String NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_CUSTOMER_ID_CACHE_KEY = "NexinvoiceCustomerConfig:byCustomerId"
    private static final String NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_PUBLIC_ID_CACHE_KEY = "NexinvoiceCustomerConfig:byPublicId"

    @Cacheable(value = NexinvoiceCustomerConfigCacheService.NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_CUSTOMER_ID_CACHE_KEY)
    public NexinvoiceCustomerConfigCacheVO byCustomerId(Long customerId) {
        NexinvoiceCustomerConfig nexinvoiceCustomerConfig = NexinvoiceCustomerConfig.query([customerId: customerId]).get()

        if (!nexinvoiceCustomerConfig) return new NexinvoiceCustomerConfigCacheVO()

        return NexinvoiceCustomerConfigCacheVO.build(nexinvoiceCustomerConfig)
    }

    @Cacheable(value = NexinvoiceCustomerConfigCacheService.NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_PUBLIC_ID_CACHE_KEY)
    public NexinvoiceCustomerConfigCacheVO byPublicId(String publicId) {
        NexinvoiceCustomerConfig nexinvoiceCustomerConfig = NexinvoiceCustomerConfig.query([publicId: publicId]).get()

        if (!nexinvoiceCustomerConfig) return new NexinvoiceCustomerConfigCacheVO()

        return NexinvoiceCustomerConfigCacheVO.build(nexinvoiceCustomerConfig)
    }

    @CacheEvict(value = NexinvoiceCustomerConfigCacheService.NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_CUSTOMER_ID_CACHE_KEY)
    public void evictByCustomerId(Long customerId) {}

    @CacheEvict(value = NexinvoiceCustomerConfigCacheService.NEXINVOICE_CUSTOMER_CONFIG_CACHE_BY_PUBLIC_ID_CACHE_KEY)
    public void evictByPublicId(String publicId) {}
}
