package com.asaas.service.bifrost

import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class BifrostCustomerService {

    def customerCheckoutLimitService

    public BigDecimal getCustomerAvailableDailyCheckout(Customer customer) {
        BigDecimal customerBalance = customerCheckoutLimitService.getAvailableDailyCheckout(customer)
        return customerBalance
    }
}
