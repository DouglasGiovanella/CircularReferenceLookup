package com.asaas.service.customerdocument

import com.asaas.customerdocument.adapter.CustomerDocumentFileVersionAdapter
import com.asaas.domain.customerdocument.CustomerDocumentFileVersion
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class CustomerDocumentFileVersionProxyService {

    def customerDocumentFileVersionService
    def customerDocumentMigrationCacheService

    public CustomerDocumentFileVersionAdapter find(Long customerId, Map searchParams) {
        if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(customerId)) {
            AsaasLogger.warn("CustomerDocumentFileVersionProxyService.find >> Tentativa de busca de arquivo migrado para o Heimdall. Customer [${customerId}]")
            return null
        }

        CustomerDocumentFileVersion customerDocumentFileVersion = customerDocumentFileVersionService.find(searchParams)
        if (!customerDocumentFileVersion) return null

        return new CustomerDocumentFileVersionAdapter(customerDocumentFileVersion)
    }
}
