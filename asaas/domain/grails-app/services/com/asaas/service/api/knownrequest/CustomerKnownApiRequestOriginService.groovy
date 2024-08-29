package com.asaas.service.api.knownrequest

import com.asaas.domain.api.knownrequest.CustomerKnownApiRequestOrigin
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.service.api.ApiBaseService
import com.asaas.utils.IpUtils
import grails.transaction.Transactional

@Transactional
class CustomerKnownApiRequestOriginService extends ApiBaseService {

    def customerKnownApiRequestCacheService

    public void save(Customer customer, String userAgent, String remoteIp) {
        if (remoteIp && !IpUtils.isIpv4(remoteIp)) throw new BusinessException("O IP ${remoteIp} informado não é válido")
        if (!remoteIp && !userAgent) throw new BusinessException("Informe ao menos uma origem")

        CustomerKnownApiRequestOrigin apiRequestOrigin = new CustomerKnownApiRequestOrigin()
        apiRequestOrigin.customer = customer
        apiRequestOrigin.remoteIp = remoteIp
        apiRequestOrigin.userAgent = userAgent
        apiRequestOrigin.save(failOnError: true)

        customerKnownApiRequestCacheService.evictCustomerOrigin(customer)
    }

    public void delete(Customer customer, Long id) {
        CustomerKnownApiRequestOrigin requestOrigin = CustomerKnownApiRequestOrigin.query([customer: customer, id: id]).get()
        if (!requestOrigin) throw new ResourceNotFoundException("Origin permitida de request API não encontrada. ID: ${id}")

        requestOrigin.deleted = true
        requestOrigin.save(failOnError: true)

        customerKnownApiRequestCacheService.evictCustomerOrigin(customer)
    }

    public Boolean isKnownOrigin(Customer customer, String userAgent, String remoteIp) {
        List<Map> knownOrigins = customerKnownApiRequestCacheService.listCustomerKnownOrigins(customer)
        if (!knownOrigins) return true

        Boolean isKnownRemoteIp = knownOrigins.any({ it.remoteIp && it.remoteIp == remoteIp })
        if (isKnownRemoteIp) return true

        Boolean isKnownUserAgent = knownOrigins.any({ it.userAgent && it.userAgent == userAgent })
        if (isKnownUserAgent) return true

        return false
    }
}
