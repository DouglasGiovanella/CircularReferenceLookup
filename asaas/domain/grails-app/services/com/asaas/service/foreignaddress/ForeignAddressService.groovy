package com.asaas.service.foreignaddress

import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.foreignaddress.ForeignAddress

import grails.transaction.Transactional

@Transactional
class ForeignAddressService {

    public ForeignAddress saveOrUpdate(CustomerAccount customerAccount, String country, String city, String state) {
 		ForeignAddress foreignAddress = ForeignAddress.findOrCreateWhere(customerAccount: customerAccount)

 		if (country != null) {
 			foreignAddress.country = country
 		}

		if (city != null) {
 			foreignAddress.city = city
 		}

 		if (state != null) {
 			foreignAddress.state = state
 		}

 		foreignAddress.save(flush: true, failOnError: true)

 		return foreignAddress
    }

    public void delete(CustomerAccount customerAccount) {
        ForeignAddress foreignAddress = ForeignAddress.find(customerAccount)
        if (foreignAddress) foreignAddress.delete(flush: true, failOnError: true)
    }
}
