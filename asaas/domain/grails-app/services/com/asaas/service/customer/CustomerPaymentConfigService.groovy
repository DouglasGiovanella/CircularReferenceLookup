package com.asaas.service.customer

import com.asaas.customer.CustomerPaymentConfigPropertiesBuilder
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerPaymentConfig
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class CustomerPaymentConfigService {

    public CustomerPaymentConfig saveOrUpdate(Customer customer, Map params) {
        CustomerPaymentConfig validatedCustomerPaymentConfig = validateSaveOrUpdateParams(params)
        if (validatedCustomerPaymentConfig.hasErrors()) return validatedCustomerPaymentConfig

        Map properties = CustomerPaymentConfigPropertiesBuilder.build(params)

        CustomerPaymentConfig customerPaymentConfig = CustomerPaymentConfig.findOrCreateByCustomer(customer)
        customerPaymentConfig.properties = properties

        customerPaymentConfig.save(flush: true, failOnError: false)

        return customerPaymentConfig
    }

    private CustomerPaymentConfig validateSaveOrUpdateParams(Map params) {
        CustomerPaymentConfig customerPaymentConfig = new CustomerPaymentConfig()

        if (Boolean.valueOf(params.showNotarialProtestMessage) && (!params.daysToProtest?.isInteger() || Integer.valueOf(params.daysToProtest) <= 0)) {
            DomainUtils.addError(customerPaymentConfig, "Informe o número de dias para protesto.")
        }

        return customerPaymentConfig
    }
}