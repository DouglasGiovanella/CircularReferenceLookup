package com.asaas.service.customeronboarding

import com.asaas.domain.customer.Customer
import com.asaas.domain.customeronboarding.CustomerOnboarding
import grails.transaction.Transactional

@Transactional
class CustomerOnboardingService {

    def customerOnboardingStepService

    public void create(Customer customer) {
        CustomerOnboarding customerOnboarding = new CustomerOnboarding()
        customerOnboarding.finished = false
        customerOnboarding.customer = customer
        customerOnboarding.save(failOnError: true)

        customerOnboardingStepService.createAll(customerOnboarding)
    }

    public void finish(Long customerId) {
        CustomerOnboarding customerOnboarding = CustomerOnboarding.query([customerId: customerId]).get()

        if (!customerOnboarding) return

        customerOnboarding.finished = true
        customerOnboarding.save(failOnError: true)
    }

    public Boolean hasFinished(Long customerId) {
        return CustomerOnboarding.query([column: "finished", customerId: customerId]).get()
    }
}
