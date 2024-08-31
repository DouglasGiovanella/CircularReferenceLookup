package com.asaas.service.hubspot

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.environment.AsaasEnvironment
import com.asaas.externalidentifier.ExternalApplication

import grails.transaction.Transactional

@Transactional
class HubspotService {

    def externalIdentifierCacheService

    public Boolean canSendInfoToHubspot(Customer customer) {
        if (AsaasEnvironment.isSandbox()) return false

        if (customer.accountOwner) return false

        if (CustomerParameter.getValue(customer, CustomerParameterName.DISABLE_HUBSPOT_INTEGRATION)) return false

        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: 'originChannel', customer: customer]).get()
        if (customerSignUpOriginChannel?.isApi()) return false

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) return false

        return true
    }

    public Boolean canSendInfoToHubspot(String contactId) {
        if (!contactId) return false
        if (AsaasEnvironment.isSandbox()) return false

        return true
    }

    public String getHubspotContactIdIfExists(String email) {
        Long customerId = Customer.query([column: "id", "email[eq]": email]).get()
        if (!customerId) return null

        return getHubspotContactId(customerId)
    }

    public String getHubspotContactId(Long customerId) {
        String contactId = externalIdentifierCacheService.getExternalIdentifier(customerId, ExternalApplication.HUBSPOT)
        return contactId
    }

    public Boolean hasContactId(String email) {
        Long customerId = Customer.query([column: "id", "email[eq]": email]).get()
        if (!customerId) return false

        return externalIdentifierCacheService.getExternalIdentifier(customerId, ExternalApplication.HUBSPOT).asBoolean()
    }
}
