package com.asaas.service.pix

import com.asaas.domain.customer.Customer
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.adapter.claim.PixAddressKeyExternalClaimListAdapter
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyExternalClaimAdapter

import grails.transaction.Transactional

@Transactional
class PixAddressKeyExternalClaimService {

    def pixAddressKeyExternalClaimManagerService

    public PixCustomerAddressKeyExternalClaimAdapter find(String id, Customer customer) {
        return pixAddressKeyExternalClaimManagerService.find(id, customer)
    }

    public PixAddressKeyExternalClaimListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        return pixAddressKeyExternalClaimManagerService.list(customer, filters, limit, offset)
    }

    public PixCustomerAddressKeyExternalClaimAdapter cancel(Customer customer, String id, PixAddressKeyClaimCancellationReason cancellationReason) {
        return pixAddressKeyExternalClaimManagerService.cancel(customer, id, cancellationReason)
    }

    public PixCustomerAddressKeyExternalClaimAdapter approve(Customer customer, String id) {
        return pixAddressKeyExternalClaimManagerService.approve(customer, id)
    }
}
