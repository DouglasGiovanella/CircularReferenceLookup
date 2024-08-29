package com.asaas.service.customer

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBoletoBankInfo
import grails.transaction.Transactional

@Transactional
class CustomerBoletoBankInfoService {

    public CustomerBoletoBankInfo save(Customer customer, String reason) {
        CustomerBoletoBankInfo customerBoletoBankInfo = CustomerBoletoBankInfo.query([customer: customer]).get()

        if (!customerBoletoBankInfo) {
            customerBoletoBankInfo = new CustomerBoletoBankInfo()
            customerBoletoBankInfo.customer = customer
        }

        customerBoletoBankInfo.boletoBank = customer.boletoBank
        customerBoletoBankInfo.reason = reason
        customerBoletoBankInfo.reasonDate = new Date()
        customerBoletoBankInfo.save(failOnError: true, flush: true)

        return customerBoletoBankInfo
    }
}
