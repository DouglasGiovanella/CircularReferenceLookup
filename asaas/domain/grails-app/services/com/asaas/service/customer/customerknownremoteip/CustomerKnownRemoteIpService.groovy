package com.asaas.service.customer.customerknownremoteip

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.customerknownremoteip.CustomerKnownRemoteIp
import com.asaas.originrequesterinfo.EventOriginType
import grails.transaction.Transactional

@Transactional
class CustomerKnownRemoteIpService {

    public CustomerKnownRemoteIp findOrCreate(Customer customer, String remoteIp, EventOriginType eventOrigin) {
        CustomerKnownRemoteIp customerKnownRemoteIp = CustomerKnownRemoteIp.query([customerId: customer.id, remoteIp: remoteIp]).get()
        if (customerKnownRemoteIp) {
            return customerKnownRemoteIp
        }

        customerKnownRemoteIp = new CustomerKnownRemoteIp()
        customerKnownRemoteIp.customer = customer
        customerKnownRemoteIp.remoteIp = remoteIp
        customerKnownRemoteIp.eventOrigin = eventOrigin
        customerKnownRemoteIp.trustedToCheckout = false
        customerKnownRemoteIp.save(flush: true)

        return customerKnownRemoteIp
    }

    public void setAsTrustedToCheckoutIfPossible(Customer customer, String remoteIp, EventOriginType eventOrigin) {
        CustomerKnownRemoteIp customerKnownRemoteIp = findOrCreate(customer, remoteIp, eventOrigin)
        setAsTrustedToCheckoutInBatch([customerKnownRemoteIp.id])
    }

    public void setAsTrustedToCheckoutInBatch(List<Long> customerKnownRemoteIpIdList) {
        if (!customerKnownRemoteIpIdList) return

        Map updateParams = [
            lastUpdated: new Date(),
            idList: customerKnownRemoteIpIdList
        ]

        CustomerKnownRemoteIp.executeUpdate("UPDATE CustomerKnownRemoteIp SET version = version + 1, lastUpdated = :lastUpdated, trustedToCheckout = true WHERE id IN (:idList)", updateParams)
    }
}
