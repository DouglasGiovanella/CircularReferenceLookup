package com.asaas.service.api.knownrequest

import com.asaas.domain.api.knownrequest.CustomerKnownApiRequestLocationWhitelist
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.service.api.ApiBaseService
import grails.transaction.Transactional

@Transactional
class CustomerKnownApiRequestLocationWhitelistService extends ApiBaseService {

    def customerKnownApiRequestCacheService

    public void save(Customer customer, String country, String city) {
        if (!country && !city) throw new BusinessException("Informe a cidade e país")

        CustomerKnownApiRequestLocationWhitelist whitelist = new CustomerKnownApiRequestLocationWhitelist()
        whitelist.customer = customer
        whitelist.country = country
        whitelist.city = city
        whitelist.save(failOnError: true)

        customerKnownApiRequestCacheService.evictCustomerLocation(customer)
    }

    public void delete(Customer customer, Long id) {
        CustomerKnownApiRequestLocationWhitelist requestLocation = CustomerKnownApiRequestLocationWhitelist.query([customer: customer, id: id]).get()
        if (!requestLocation) throw new ResourceNotFoundException("Localização permitida de request API não encontrada. ID: ${id}")

        requestLocation.deleted = true
        requestLocation.save(failOnError: true)

        customerKnownApiRequestCacheService.evictCustomerLocation(customer)
    }

    public Boolean isKnownLocation(Customer customer, String country, String city) {
        List<Map> customerWhitelist = customerKnownApiRequestCacheService.listCustomerKnownLocations(customer)
        if (!customerWhitelist) return true

        Boolean isKnownLocation = customerWhitelist.any({ it.country == country && it.city == city })
        if (isKnownLocation) return true

        return false
    }
}
