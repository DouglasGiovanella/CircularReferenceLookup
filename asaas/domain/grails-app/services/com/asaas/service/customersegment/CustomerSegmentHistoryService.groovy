package com.asaas.service.customersegment

import com.asaas.customer.CustomerSegment
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSegmentHistory

import grails.transaction.Transactional

@Transactional
class CustomerSegmentHistoryService {

    public CustomerSegmentHistory save(Customer customer, CustomerSegment newSegment) {
        CustomerSegmentHistory customerSegmentHistory = new CustomerSegmentHistory([customer: customer, newSegment: newSegment])
        customerSegmentHistory.save(flush: true, failOnError: true)
    }
}
