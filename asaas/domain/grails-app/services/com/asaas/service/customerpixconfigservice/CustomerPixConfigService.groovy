package com.asaas.service.customerpixconfigservice

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerPixConfig

import grails.transaction.Transactional

@Transactional
class CustomerPixConfigService {

    public CustomerPixConfig save(Customer customer) {
        CustomerPixConfig customerPixConfig = new CustomerPixConfig()
        customerPixConfig.customer = customer
        customerPixConfig.canReceivePayment = false
        customerPixConfig.paymentReceivingWithPixDisabled = false
        customerPixConfig.save(failOnError: true)

        return customerPixConfig
    }

    public CustomerPixConfig update(Map pixConfig, Customer customer) {
        CustomerPixConfig customerPixConfig = CustomerPixConfig.query([customer: customer]).get()

        if (!customerPixConfig) customerPixConfig = save(customer)

        if (pixConfig.containsKey("canReceivePayment")) customerPixConfig.canReceivePayment = pixConfig.canReceivePayment
        if (pixConfig.containsKey("addressKeyCreated")) customerPixConfig.addressKeyCreated = pixConfig.addressKeyCreated
        if (pixConfig.containsKey("paymentReceivingWithPixDisabled")) customerPixConfig.paymentReceivingWithPixDisabled = pixConfig.paymentReceivingWithPixDisabled

        customerPixConfig.save(failOnError: true)

        return customerPixConfig
    }

}
