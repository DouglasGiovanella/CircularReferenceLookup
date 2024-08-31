package com.asaas.service.customer

import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerEventName
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.CustomerEvent
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class CustomerEventService {

    def customerEventCacheService
    def hubspotEventService

    public CustomerEvent save(Customer customer, CustomerEventName event, AccountManager accountManager, Date eventDate) {
        CustomerEvent customerEvent = new CustomerEvent()

        customerEvent.customer = customer
        customerEvent.event = event
        customerEvent.dateCreated = eventDate ?: new Date()
        customerEvent.accountManager = accountManager
        customerEvent.save()

        return customerEvent
    }

    public Boolean hasCreatedPaymentByBillingType(Customer customer, BillingType billingType) {
        Boolean hasCreatedPaymentByBillingType = false
        CustomerEventName eventName = billingType.convertToFirstPaymentCreatedCustomerEventName()
        if (!eventName) {
            AsaasLogger.error("CustomerEventService.hasCreatedPaymentByBillingType >> Evento de criação de cobrança está com eventName nulo. BillingType: [${billingType}]. CustomerId: [${customer.id}]")
            return hasCreatedPaymentByBillingType
        }

        Boolean hasEventFirstPaymentCreatedOnMethod = customerEventCacheService.hasEventFirstPaymentCreatedOnMethod(customer, eventName)
        if (hasEventFirstPaymentCreatedOnMethod) hasCreatedPaymentByBillingType = true
        return hasCreatedPaymentByBillingType
    }

    public void saveFirstPaymentReceivedOnMethodIfPossible(Customer customer, BillingType billingType) {
        if (!billingType) {
            AsaasLogger.error("CustomerEventService.saveFirstPaymentReceivedOnMethodIfPossible >> Evento de recebimento de cobrança está com billingType nulo. CustomerId: [${customer.id}]")
            return
        }

        if (!billingType.haveRelatedCustomerEventName()) return

        CustomerEventName eventName = billingType.convertToFirstPaymentReceivedCustomerEventName()
        if (!eventName) {
            AsaasLogger.error("CustomerEventService.saveFirstPaymentReceivedOnMethodIfPossible >> Evento de recebimento de cobrança está com eventName nulo. BillingType: [${billingType}]. CustomerId: [${customer.id}]")
            return
        }

        Boolean hasEventFirstPaymentReceivedOnMethod = customerEventCacheService.hasEventFirstPaymentReceivedOnMethod(customer, eventName)
        if (hasEventFirstPaymentReceivedOnMethod) return

        hubspotEventService.trackCustomerHasPaymentReceived(customer, billingType)
        save(customer, eventName, null, new Date())
        customerEventCacheService.evictHasEventFirstPaymentReceivedOnMethod(customer.id, eventName)
    }

    public void saveFirstPaymentCreatedOnMethod(Customer customer, BillingType billingType) {
        CustomerEventName eventName = billingType.convertToFirstPaymentCreatedCustomerEventName()
        hubspotEventService.trackCustomerHasPaymentCreated(customer, billingType)
        save(customer, eventName, null, new Date())
    }
}
