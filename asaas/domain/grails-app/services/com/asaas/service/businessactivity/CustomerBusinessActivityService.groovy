package com.asaas.service.businessactivity

import com.asaas.domain.businessactivity.BusinessActivity
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity

import grails.transaction.Transactional

@Transactional
class CustomerBusinessActivityService {

    def newChildAccountCreditCardDynamicMccFeeConfigReplicationService

    public CustomerBusinessActivity save(Customer customer, Long businessActivityId, String businessActivityDescription) {
        CustomerBusinessActivity customerBusinessActivity = CustomerBusinessActivity.query([customer: customer]).get()

        if (!customerBusinessActivity) customerBusinessActivity = new CustomerBusinessActivity(customer: customer)

        customerBusinessActivity.businessActivity = BusinessActivity.get(businessActivityId)
        customerBusinessActivity.businessActivityDescription = businessActivityDescription
        customerBusinessActivity.save(failOnError: true)

        newChildAccountCreditCardDynamicMccFeeConfigReplicationService.saveReplicationIfPossible(customer)

        return customerBusinessActivity
    }
}
