package com.asaas.service.invoice

import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.CustomerInvoiceOnboarding
import com.asaas.invoice.CustomerInvoiceOnboardingStep

import grails.transaction.Transactional

@Transactional
class CustomerInvoiceOnboardingService {

    public CustomerInvoiceOnboarding saveOnboardingCurrentStep(Customer customer, CustomerInvoiceOnboardingStep onboardingStep) {
        CustomerInvoiceOnboarding customerInvoiceOnboarding = CustomerInvoiceOnboarding.query([customer: customer]).get() ?: new CustomerInvoiceOnboarding(customer: customer)
        customerInvoiceOnboarding.currentStep = onboardingStep
        customerInvoiceOnboarding.save(failOnError: true)

        return customerInvoiceOnboarding
    }

    public CustomerInvoiceOnboardingStep getCurrentStep(Customer customer) {
        CustomerInvoiceOnboarding customerInvoiceOnboarding = CustomerInvoiceOnboarding.query([customer: customer]).get()
        if (customerInvoiceOnboarding) return customerInvoiceOnboarding.currentStep

        return CustomerInvoiceOnboardingStep.AUTHORIZATION_INFO
    }

    public static Boolean isInvoiceOnboardingPending(Customer customer) {
        CustomerInvoiceOnboardingStep invoiceOnboardingCurrentStep = CustomerInvoiceOnboarding.query([column: "currentStep", customer: customer]).get()
        if (!invoiceOnboardingCurrentStep) return false
        return !invoiceOnboardingCurrentStep.isCompleted()
    }
}
