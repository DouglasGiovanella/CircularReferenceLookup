package com.asaas.service.cache.paymentsplit

import com.asaas.domain.split.PaymentSplit

import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class PaymentSplitCacheService {

    def grailsCacheManager
    def beamerService

    @Cacheable(value = "PaymentSplit:isCustomerWithPaidPaymentSplit")
    public Boolean isCustomerWithPaidPaymentSplit(Long originCustomerId) {
        Boolean hasPaidPaymentSplit = PaymentSplit.query([exists: true, originCustomerId: originCustomerId]).get().asBoolean()

        return hasPaidPaymentSplit
    }

    public void evictIsCustomerWithPaidPaymentSplit(Long originCustomerId) {
        final String cacheName = "PaymentSplit:isCustomerWithPaidPaymentSplit"
        grailsCacheManager.getCache(cacheName).evict(originCustomerId)
    }

    @Cacheable(value = "PaymentSplit:isCustomerWithReceivedPaymentSplit")
    public Boolean isCustomerWithReceivedPaymentSplit(Long destinationCustomerId) {
        Boolean hasReceivedPaymentSplit = PaymentSplit.query([exists: true, destinationCustomerId: destinationCustomerId]).get().asBoolean()

        return hasReceivedPaymentSplit
    }

    public void evictIsCustomerWithReceivedPaymentSplit(Long destinationCustomerId) {
        final String cacheName = "PaymentSplit:isCustomerWithReceivedPaymentSplit"
        grailsCacheManager.getCache(cacheName).evict(destinationCustomerId)
    }
}
