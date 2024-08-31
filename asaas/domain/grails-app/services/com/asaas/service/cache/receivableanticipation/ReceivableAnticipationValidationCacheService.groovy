package com.asaas.service.cache.receivableanticipation

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationAgreement
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationValidationCacheService {

    def grailsCacheManager

    @Cacheable(value = "ReceivableAnticipationValidation:isCustomerWithPartnerSettlementAwaitingCreditForTooLong")
    public Boolean isCustomerWithPartnerSettlementAwaitingCreditForTooLong(Long customerId) {
        Boolean isCustomerWithPartnerSettlementAwaitingCredit = ReceivableAnticipationValidator.isCustomerWithPartnerSettlementAwaitingCreditForTooLongWithoutCache(customerId)
        return isCustomerWithPartnerSettlementAwaitingCredit
    }

    public void evictIsCustomerWithPartnerSettlementAwaitingCreditForTooLong(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isCustomerWithPartnerSettlementAwaitingCreditForTooLong"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:isCustomerWithPartnerSettlementAwaitingCredit")
    public Boolean isCustomerWithPartnerSettlementAwaitingCredit(Long customerId) {
        return ReceivableAnticipationPartnerSettlement.awaitingCredit([exists: true, customerId: customerId]).get().asBoolean()
    }

    public void evictIsCustomerWithPartnerSettlementAwaitingCredit(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isCustomerWithPartnerSettlementAwaitingCredit"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:isBankSlipEnabled")
    public Boolean isBankSlipEnabled(Long customerId) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)
        return receivableAnticipationConfig.bankSlipEnabled
    }

    @Cacheable(value = "ReceivableAnticipationValidation:isCreditCardEnabled")
    public Boolean isCreditCardEnabled(Long customerId) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)
        return receivableAnticipationConfig.creditCardEnabled
    }

    public void evictIsBankSlipEnabled(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isBankSlipEnabled"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    public void evictIsCreditCardEnabled(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isCreditCardEnabled"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:isFirstUse")
    public Boolean isFirstUse(Long customerId) {
        Customer customer = Customer.read(customerId)

        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return true
        if (!customer.hasCreatedPayments()) return true
        if (ReceivableAnticipationValidator.requiresConfirmedTransferToAnticipate(customer) && !ReceivableAnticipationValidator.hasConfirmedTransfer(customer)) return true
        if (customer.isLegalPerson()) return false

        Integer daysToAnticipateCreditCard = ReceivableAnticipationCalculator.calculateDaysToEnableCreditCardAnticipation(customer)
        if (daysToAnticipateCreditCard > 0) return true

        return false
    }

    public void evictIsFirstUse(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isFirstUse"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:anyAgreementVersionHasBeenSigned")
    public Boolean anyAgreementVersionHasBeenSigned(Long customerId) {
        return ReceivableAnticipationAgreement.anyAgreementVersionHasBeenSigned(customerId)
    }

    public void evictAnyAgreementVersionHasBeenSigned(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:anyAgreementVersionHasBeenSigned"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:isAutomaticActivated")
    public Boolean isAutomaticActivated(Long customerId) {
        return CustomerAutomaticReceivableAnticipationConfig.activated([exists: true, customerId: customerId]).get().asBoolean()
    }

    public void evictIsAutomaticActivated(Long customerId) {
        final String cacheName = "ReceivableAnticipationValidation:isAutomaticActivated"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    @Cacheable(value = "ReceivableAnticipationValidation:hasActiveReceivableAnticipation")
    public Boolean hasActiveReceivableAnticipation(Long customerAccountId) {
        return ReceivableAnticipation.query([exists: true, customerAccountId: customerAccountId, statusList: ReceivableAnticipationStatus.getStillNotDebitedStatus()]).get().asBoolean()
    }

    @CacheEvict(value = "ReceivableAnticipationValidation:hasActiveReceivableAnticipation")
    public void evictHasActiveReceivableAnticipation(Long customerAccountId) { }
}
