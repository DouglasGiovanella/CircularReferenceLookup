package com.asaas.service.customerdocumentmigration

import com.asaas.applicationconfig.AsaasApplicationHolder
import grails.transaction.Transactional

@Transactional
class CustomerDocumentMigrationService {

    def heimdallAccountDocumentMigrationManagerService

    public Boolean hasDocumentationInHeimdall(Long customerId) {
        return heimdallAccountDocumentMigrationManagerService.exists(customerId)
    }

    public void toggle(Long customerId, Boolean enabled) {
        heimdallAccountDocumentMigrationManagerService.toggle(customerId, enabled)
        AsaasApplicationHolder.applicationContext.customerDocumentMigrationCacheService.evict(customerId)
    }
}
