package com.asaas.service.cache.customer

import com.asaas.domain.customer.CustomerFeature
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerFeatureCacheService {

    @Cacheable(value = "CustomerFeature:canHandleBillingInfoByCustomerId")
    public Boolean canHandleBillingInfoEnabled(Long customerId) {
        return CustomerFeature.query([column: "canHandleBillingInfo", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:multipleBankAccountsByCustomerId")
    public Boolean isMultipleBankAccountsEnabled(Long customerId) {
        return CustomerFeature.query([column: "multipleBankAccounts", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:billPaymentByCustomerId")
    public Boolean isBillPaymentEnabled(Long customerId) {
        return CustomerFeature.query([column: "billPayment", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:invoiceByCustomerId")
    public Boolean isInvoiceEnabled(Long customerId) {
        return CustomerFeature.query([column: "invoice", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:asaasCardEloByCustomerId")
    public Boolean isAsaasCardEloEnabled(Long customerId) {
        return CustomerFeature.query([column: "asaasCardElo", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:pixWithAsaasKeyByCustomerId")
    public Boolean isPixWithAsaasKeyEnabled(Long customerId) {
        return CustomerFeature.query([column: "pixWithAsaasKey", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @Cacheable(value = "CustomerFeature:whatsappNotificationByCustomerId")
    public Boolean isWhatsappNotificationEnabled(Long customerId) {
        return CustomerFeature.query([column: "whatsappNotification", customerId: customerId, disableSort: true]).get().asBoolean()
    }

    @CacheEvict(value = "CustomerFeature:canHandleBillingInfoByCustomerId")
    public void evictCanHandleBillingInfo(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:multipleBankAccountsByCustomerId")
    public void evictMultipleBankAccountsByCustomerId(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:billPaymentByCustomerId")
    public void evictBillPaymentByCustomerId(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:invoiceByCustomerId")
    public void evictInvoiceByCustomerId(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:asaasCardEloByCustomerId")
    public void evictAsaasCardEloByCustomerId(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:pixWithAsaasKeyByCustomerId")
    public void evictPixWithAsaasKeyByCustomerId(Long customerId) {}

    @CacheEvict(value = "CustomerFeature:whatsappNotificationByCustomerId")
    public void evictWhatsappNotificationByCustomerId(Long customerId) {}
}
