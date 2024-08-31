package com.asaas.service.nexinvoice

import com.asaas.domain.customer.Customer
import com.asaas.domain.nexinvoice.NexinvoiceCustomerConfig
import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerConfigService {

    def nexinvoiceCustomerConfigCacheService
    def nexinvoiceCustomerDisableAsyncActionService
    def nexinvoiceUserConfigService

    public void create(Customer customer, String publicId) {
        NexinvoiceCustomerConfig nexinvoiceCustomerConfig = new NexinvoiceCustomerConfig()

        nexinvoiceCustomerConfig.customer = customer
        nexinvoiceCustomerConfig.publicId = publicId
        nexinvoiceCustomerConfig.isIntegrated = false

        nexinvoiceCustomerConfig.save(failOnError: true)
        invalidateCache(nexinvoiceCustomerConfig)
    }

    public void update(Customer customer, Long externalId, Boolean isIntegrated) {
        NexinvoiceCustomerConfig nexinvoiceCustomerConfig = NexinvoiceCustomerConfig.findByCustomer(customer)

        if (!nexinvoiceCustomerConfig) {
            throw new RuntimeException("NexinvoiceCustomerConfig not found for customer: ${customer.id}")
        }

        nexinvoiceCustomerConfig.externalId = externalId
        nexinvoiceCustomerConfig.isIntegrated = isIntegrated
        nexinvoiceCustomerConfig.save(failOnError: true)

        invalidateCache(nexinvoiceCustomerConfig)
    }

    public void delete(Long customerId) {
        NexinvoiceCustomerConfig nexinvoiceCustomerConfig = NexinvoiceCustomerConfig.query([customerId: customerId]).get()
        if (!nexinvoiceCustomerConfig) return

        nexinvoiceCustomerConfig.deleted = true
        nexinvoiceCustomerConfig.save(failOnError: true)
        invalidateCache(nexinvoiceCustomerConfig)

        nexinvoiceUserConfigService.deleteCustomerUserConfig(customerId)

        nexinvoiceCustomerDisableAsyncActionService.save(nexinvoiceCustomerConfig.publicId)
    }

    private void invalidateCache(NexinvoiceCustomerConfig nexinvoiceCustomerConfig) {
        nexinvoiceCustomerConfigCacheService.evictByCustomerId(nexinvoiceCustomerConfig.customer.id)
        nexinvoiceCustomerConfigCacheService.evictByPublicId(nexinvoiceCustomerConfig.publicId)
    }
}
