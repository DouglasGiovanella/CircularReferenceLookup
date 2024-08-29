package com.asaas.service.knownyourcustomer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequest
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequestBatch
import com.asaas.knownyourcustomer.KnownYourCustomerRequestStatus
import grails.transaction.Transactional

@Transactional
class KnownYourCustomerRequestService {

    public KnownYourCustomerRequest createRequestIfNecessary(Customer customer) {
        if (!customer.accountOwner) return null

        BigDecimal childAccountKnownYourCustomerFee = CustomerFee.calculateChildAccountKnownYourCustomerFeeValue(customer.accountOwner)

        if (!childAccountKnownYourCustomerFee) return null
        if (childAccountKnownYourCustomerFee <= 0) return null

        KnownYourCustomerRequest knownYourCustomerRequest = new KnownYourCustomerRequest()
        knownYourCustomerRequest.fee = childAccountKnownYourCustomerFee
        knownYourCustomerRequest.customer = customer
        knownYourCustomerRequest.accountOwner = customer.accountOwner
        knownYourCustomerRequest.save(failOnError: true)

        return knownYourCustomerRequest
    }

    public void setAsProcessed(Long knownYourCustomerRequestId, KnownYourCustomerRequestBatch knownYourCustomerRequestBatch) {
        KnownYourCustomerRequest knownYourCustomerRequest = KnownYourCustomerRequest.get(knownYourCustomerRequestId)
        if (!knownYourCustomerRequestId) return

        knownYourCustomerRequest.status = KnownYourCustomerRequestStatus.PROCESSED
        knownYourCustomerRequest.batch = knownYourCustomerRequestBatch
        knownYourCustomerRequest.save(flush: true, failOnError: true)
    }
}
