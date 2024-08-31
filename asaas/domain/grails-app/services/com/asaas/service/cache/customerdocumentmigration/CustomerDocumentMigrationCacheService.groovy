package com.asaas.service.cache.customerdocumentmigration

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerDocumentMigrationCacheService {

    static final String CUSTOMER_DOCUMENT_MIGRATION_BY_CUSTOMER_ID_CACHE_KEY = "CustomerDocumentMigration:byCustomerId"

    def customerDocumentMigrationService

    @Cacheable(value = CustomerDocumentMigrationCacheService.CUSTOMER_DOCUMENT_MIGRATION_BY_CUSTOMER_ID_CACHE_KEY, key = "#customerId")
    public Boolean hasDocumentationInHeimdall(Long customerId) {
        return customerDocumentMigrationService.hasDocumentationInHeimdall(customerId)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = CustomerDocumentMigrationCacheService.CUSTOMER_DOCUMENT_MIGRATION_BY_CUSTOMER_ID_CACHE_KEY, key="#customerId")
    public void evict(Long customerId) { }
}
