package com.asaas.service.customer


import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBoletoConfig
import grails.transaction.Transactional

@Transactional
class CustomerBoletoConfigService {

    def customerParameterService

    public CustomerBoletoConfig save(Customer customer, Map boletoConfig) {
        CustomerBoletoConfig customerBoletoConfig = CustomerBoletoConfig.findOrCreateByCustomer(customer)

        if (boletoConfig.containsKey("hideContactInfo")) {
            customerBoletoConfig.hideContactInfo = boletoConfig.hideContactInfo
        }

        if (boletoConfig.containsKey("hideInvoiceUrl")) {
            customerBoletoConfig.hideInvoiceUrl = boletoConfig.hideInvoiceUrl
        }

        if (boletoConfig.containsKey("customTemplate")) {
            customerBoletoConfig.customTemplate = boletoConfig.customTemplate
        }

        if (boletoConfig.containsKey("customLogo")) {
            customerBoletoConfig.customLogo = boletoConfig.customLogo
        }

        if (boletoConfig.containsKey("showCustomerInfo")) {
            customerBoletoConfig.showCustomerInfo = boletoConfig.showCustomerInfo
        }

        if (boletoConfig.containsKey("bankSlipOnPageBottom")) {
            customerBoletoConfig.bankSlipOnPageBottom = boletoConfig.bankSlipOnPageBottom
        }

        customerBoletoConfig.save(failOnError: true)

        return customerBoletoConfig
    }
}
