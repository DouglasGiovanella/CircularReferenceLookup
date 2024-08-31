package com.asaas.service.customer.customerevent

import com.asaas.customer.CustomerEventName
import com.asaas.domain.customer.CustomerEvent
import com.asaas.domain.customer.Customer

import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CustomerEventCacheService {

    def grailsCacheManager

    @Cacheable(value = "CustomerEvent:hasEventCreatedPayments", key = "#customer.id")
    public Boolean hasEventCreatedPayments(Customer customer) {
        Boolean hasEventPaymentCreated = CustomerEvent.query([exists: true, customer: customer, event: CustomerEventName.PAYMENT_CREATED]).get().asBoolean()
        return hasEventPaymentCreated
    }

    @Cacheable(value = "CustomerEvent:hasEventFirstPaymentCreatedOnMethod", key = "#root.target.generateCacheKey(#customer.id, #eventName)")
    public Boolean hasEventFirstPaymentCreatedOnMethod(Customer customer, CustomerEventName eventName) {
        Boolean hasEventFirstPaymentCreatedOnMethod = CustomerEvent.query([exists: true, customer: customer, event: eventName]).get().asBoolean()
        return hasEventFirstPaymentCreatedOnMethod
    }

    @Cacheable(value = "CustomerEvent:hasEventFirstPaymentReceivedOnMethod", key = "#root.target.generateCacheKey(#customer.id, #eventName)")
    public Boolean hasEventFirstPaymentReceivedOnMethod(Customer customer, CustomerEventName eventName) {
        Boolean hasEventFirstPaymentReceivedOnMethod = CustomerEvent.query([exists: true, customer: customer, event: eventName]).get().asBoolean()
        return hasEventFirstPaymentReceivedOnMethod
    }

    public String generateCacheKey(Long customerId, CustomerEventName eventName) {
        return "CUSTOMER:${customerId}:EVENTNAME:${eventName}"
    }

    public void evictHasEventCreatedPayments(Long customerId) {
        final String cacheName = "CustomerEvent:hasEventCreatedPayments"
        grailsCacheManager.getCache(cacheName).evict(customerId)
    }

    public void evictHasEventFirstPaymentCreatedOnMethod(Long customerId, CustomerEventName eventName) {
        final String cacheName = "CustomerEvent:hasEventFirstPaymentCreatedOnMethod"
        grailsCacheManager.getCache(cacheName).evict("CUSTOMER:${customerId}:EVENTNAME:${eventName}")
    }

    public void evictHasEventFirstPaymentReceivedOnMethod(Long customerId, CustomerEventName eventName) {
        final String cacheName = "CustomerEvent:hasEventFirstPaymentReceivedOnMethod"
        grailsCacheManager.getCache(cacheName).evict("CUSTOMER:${customerId}:EVENTNAME:${eventName}")
    }
}