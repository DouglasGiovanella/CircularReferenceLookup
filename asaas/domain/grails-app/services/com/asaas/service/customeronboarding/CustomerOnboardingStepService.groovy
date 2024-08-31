package com.asaas.service.customeronboarding

import com.asaas.customeronboarding.CustomerOnboardingStepName
import com.asaas.customeronboarding.CustomerOnboardingStepStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customeronboarding.CustomerOnboarding
import com.asaas.domain.customeronboarding.CustomerOnboardingStep
import grails.transaction.Transactional

@Transactional
class CustomerOnboardingStepService {

    def createCampaignEventMessageService

    public void createAll(CustomerOnboarding customerOnboarding) {
        for (CustomerOnboardingStepName customerOnboardingStepName : CustomerOnboardingStepName.values()) {
            create(customerOnboarding, customerOnboardingStepName, CustomerOnboardingStepStatus.PENDING)
        }
    }

    public finishActivationStep(Customer customer) {
        finishStep(customer, CustomerOnboardingStepName.ACTIVATION)
    }

    public finishBankAccountStep(Customer customer) {
        finishStep(customer, CustomerOnboardingStepName.BANK_ACCOUNT)
    }

    public finishCommercialInfoStep(Customer customer) {
        finishStep(customer, CustomerOnboardingStepName.COMMERCIAL_INFO)
    }

    public finishDocumentationStep(Customer customer) {
        finishStep(customer, CustomerOnboardingStepName.DOCUMENTATION)
    }

    private void finishStep(Customer customer, CustomerOnboardingStepName name) {
        Long customerOnboardingId = CustomerOnboarding.query([column: "id", customerId: customer.id]).get()
        if (!customerOnboardingId) return

        CustomerOnboardingStep customerOnboardingStep = CustomerOnboardingStep.query([customerOnboardingId: customerOnboardingId, name: name]).get()
        if (!customerOnboardingStep) return

        if (customerOnboardingStep.status == CustomerOnboardingStepStatus.DONE) return

        customerOnboardingStep.status = CustomerOnboardingStepStatus.DONE
        customerOnboardingStep.save(failOnError: true)

        createCampaignEventMessageService.saveForOnboardingStepFinished(customer, name)
    }

    private void create(CustomerOnboarding customerOnboarding, CustomerOnboardingStepName customerOnboardingStepName, CustomerOnboardingStepStatus customerOnboardingStepStatus) {
        CustomerOnboardingStep customerOnboardingStep = new CustomerOnboardingStep()
        customerOnboardingStep.customerOnboarding = customerOnboarding
        customerOnboardingStep.name = customerOnboardingStepName
        customerOnboardingStep.status = customerOnboardingStepStatus
        customerOnboardingStep.save(failOnError: true)
    }
}
