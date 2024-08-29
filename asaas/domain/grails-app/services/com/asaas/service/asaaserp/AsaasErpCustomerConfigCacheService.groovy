package com.asaas.service.asaaserp

import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class AsaasErpCustomerConfigCacheService {

    private static final String ASAAS_ERP_CUSTOMER_CONFIG_BY_CUSTOMER_ID = "AsaasErpCustomerConfig:getInstance"

    @Cacheable(value = AsaasErpCustomerConfigCacheService.ASAAS_ERP_CUSTOMER_CONFIG_BY_CUSTOMER_ID)
    public AsaasErpCustomerConfigCacheVO getInstance(Long customerId) {
        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: customerId]).get()

        if (!asaasErpCustomerConfig) return new AsaasErpCustomerConfigCacheVO()

        return AsaasErpCustomerConfigCacheVO.build(asaasErpCustomerConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = AsaasErpCustomerConfigCacheService.ASAAS_ERP_CUSTOMER_CONFIG_BY_CUSTOMER_ID)
    public void evict(Long customerId) { }
}
