package com.asaas.service.pix

import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixAddressKeyOwnershipConfirmation
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixAddressKeyClaimCancellationReason
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.adapter.claim.PixAddressKeyClaimListAdapter
import com.asaas.pix.adapter.claim.PixCustomerAddressKeyClaimAdapter
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixAddressKeyAsaasClaimService {

    def pixAddressKeyOwnershipConfirmationService
    def pixAddressKeyService
    def pixAddressKeyClaimManagerService

    public PixCustomerAddressKeyClaimAdapter save(Customer customer, String pixKey, PixAddressKeyType pixKeyType, String ownershipConfirmationToken) {
        pixKey = PixUtils.parseKey(pixKey, pixKeyType)

        validateSave(customer, pixKey, pixKeyType, ownershipConfirmationToken)

        return pixAddressKeyClaimManagerService.save(customer, pixKey, pixKeyType)
    }

    public PixCustomerAddressKeyClaimAdapter find(String id, Customer customer) {
        return pixAddressKeyClaimManagerService.find(id, customer)
    }

    public PixAddressKeyClaimListAdapter list(Customer customer, Map filters, Integer limit, Integer offset) {
        return pixAddressKeyClaimManagerService.list(customer, filters, limit, offset)
    }

    public PixCustomerAddressKeyClaimAdapter cancel(Customer customer, String id, PixAddressKeyClaimCancellationReason cancellationReason) {
        return pixAddressKeyClaimManagerService.cancel(customer, id, cancellationReason)
    }

    private void validateSave(Customer customer, String pixKey, PixAddressKeyType pixKeyType, String ownershipConfirmationToken) {
        Map validateMap = pixAddressKeyService.validateIfCustomerCanRequestPixAddressKey(customer)
        if (!validateMap.isValid) throw new BusinessException(validateMap.errorMessage)

        if (PixAddressKeyType.getRequiresOwnershipConfirmationToken().contains(pixKeyType)) {
            PixAddressKeyOwnershipConfirmation confirmedPixAddressKeyOwnershipConfirmation = pixAddressKeyOwnershipConfirmationService.confirm(customer, pixKey, pixKeyType, ownershipConfirmationToken?.trim())
            if (confirmedPixAddressKeyOwnershipConfirmation.hasErrors()) throw new BusinessException(Utils.getMessageProperty(confirmedPixAddressKeyOwnershipConfirmation.errors.allErrors.first()))
        }
    }

}
