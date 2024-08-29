package com.asaas.service.customerpartnerapplication

import com.asaas.cache.customerpartnerapplication.CustomerPartnerApplicationCacheVO
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.partnerapplication.PartnerApplicationPermission
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerPartnerApplicationCacheService {

    @Cacheable(value = "CustomerPartnerApplication:listByCustomer", key = "#customerId")
    public CustomerPartnerApplicationCacheVO[] listByCustomer(Long customerId) {
        List<Map> customerPartnerApplicationList = CustomerPartnerApplication.query([
            columnList: ["partnerApplicationName", "permission"],
            customerId: customerId
        ]).list()

        CustomerPartnerApplicationCacheVO[] cacheVoList = []
        customerPartnerApplicationList.groupBy { it.partnerApplicationName }.each { PartnerApplicationName partnerApplicationName, List<Map> groupedCustomerPartnerApplicationList ->
            List<PartnerApplicationPermission> partnerApplicationPermissionList = groupedCustomerPartnerApplicationList.collect { it.permission }
            cacheVoList += CustomerPartnerApplicationCacheVO.build(partnerApplicationName, partnerApplicationPermissionList)
        }

        return cacheVoList
    }

    @CacheEvict(value = "CustomerPartnerApplication:listByCustomer", key = "#customerId")
    @SuppressWarnings("UnusedMethodParameter")
    public void evictByCustomer(Long customerId) {
        return // Apenas remove a chave do Redis
    }
}
