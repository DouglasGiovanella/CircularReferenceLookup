package com.asaas.service.address

import grails.transaction.Transactional
import com.asaas.domain.address.Address
import com.asaas.domain.city.City

@Transactional
class AddressService {

    public Address save(String address , String addressNumber, String complement, String postalCode, String province, City city) {
        Address newAddress = new Address()

        newAddress.address = address
        newAddress.addressNumber = addressNumber
        newAddress.complement = complement
        newAddress.postalCode = postalCode
        newAddress.province = province
        newAddress.city = city

        newAddress.save(failOnError: true)

        return newAddress
    }
}
