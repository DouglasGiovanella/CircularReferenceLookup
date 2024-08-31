package com.asaas.service.blockedcustomerurl

import com.asaas.domain.blockedcustomerurl.BlockedCustomerUrl
import com.asaas.domain.customer.Customer
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class BlockedCustomerUrlService {

    def blockedCustomerUrlCacheService

    public BlockedCustomerUrl save(Customer customer, String controller, String action) {
        final Integer blockMinutes = 5
        Date releaseDate = CustomDateUtils.sumMinutes(new Date(), blockMinutes)

        BlockedCustomerUrl blockedCustomerAccess = new BlockedCustomerUrl()
        blockedCustomerAccess.customer = customer
        blockedCustomerAccess.releaseDate = releaseDate
        blockedCustomerAccess.controller = controller
        blockedCustomerAccess.action = action
        blockedCustomerAccess.save(flush: true, failOnError: true)

        blockedCustomerUrlCacheService.save(customer.id, controller, action, releaseDate)

        return blockedCustomerAccess
    }
}
