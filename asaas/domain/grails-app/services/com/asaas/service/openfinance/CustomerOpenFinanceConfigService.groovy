package com.asaas.service.openfinance

import com.asaas.domain.openfinance.CustomerOpenFinanceConfig
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class CustomerOpenFinanceConfigService {

    public CustomerOpenFinanceConfig saveAndEnablePayments(Customer customer) {
        CustomerOpenFinanceConfig customerOpenFinanceConfig = new CustomerOpenFinanceConfig()
        customerOpenFinanceConfig.customer = customer
        customerOpenFinanceConfig.paymentsEnabled = true
        customerOpenFinanceConfig.save(failOnError: true)

        return customerOpenFinanceConfig
    }

    public Boolean hasPaymentsEnabled(Customer customer) {
        CustomerOpenFinanceConfig customerOpenFinanceConfig = CustomerOpenFinanceConfig.query([customer: customer]).get()
        if (customerOpenFinanceConfig) return customerOpenFinanceConfig.paymentsEnabled

        return true
    }
}
