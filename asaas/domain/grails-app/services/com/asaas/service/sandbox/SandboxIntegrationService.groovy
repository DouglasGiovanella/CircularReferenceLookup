package com.asaas.service.sandbox

import com.asaas.domain.customer.Customer
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.exception.BusinessException
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class SandboxIntegrationService {

    def externalIdentifierService
    def hubspotEventService
    def grailsApplication
    def grailsLinkGenerator
    def sandboxManagerService

    public String findSandboxCustomerPublicId(Customer customer) {
        ExternalIdentifier sandboxExternalIdentifier = ExternalIdentifier.query([objectId: customer.id, application: ExternalApplication.ASAAS_SANDBOX]).get()

        Map sandboxCustomerDetails = sandboxManagerService.getCustomerDetails(customer.email, customer.cpfCnpj)

        Boolean shouldDeleteExternalIdentifier = sandboxCustomerDetails.isAccountDeleted && sandboxExternalIdentifier
        if (shouldDeleteExternalIdentifier) deleteExternalIdentifier(sandboxExternalIdentifier)

        if (sandboxExternalIdentifier && !shouldDeleteExternalIdentifier) return sandboxExternalIdentifier.externalId

        if (!shouldDeleteExternalIdentifier) validateSandboxCustomer(sandboxCustomerDetails)
        validateCustomerCommercialData(customer)

        String sandboxCustomerPublicId = sandboxManagerService.createCustomer(customer)
        if (!sandboxCustomerPublicId) return null

        externalIdentifierService.save(customer, ExternalApplication.ASAAS_SANDBOX, ExternalResource.CUSTOMER, sandboxCustomerPublicId)

        hubspotEventService.trackCustomerCreateAccountInSandboxByIntegrationsPage(customer)

        return sandboxCustomerPublicId
    }

    private void validateSandboxCustomer(Map sandboxCustomerDetails) {
        String sandboxCustomerPublicId = sandboxCustomerDetails.customerPublicId
        if (sandboxCustomerPublicId) throw new BusinessException(Utils.getMessageProperty("sandboxInternal.accountAlreadyCreated", [grailsApplication.config.asaas.sandbox.login]))

        Boolean isCpfCnpjAlreadyCreated = sandboxCustomerDetails.isCpfCnpjAlreadyCreated
        if (isCpfCnpjAlreadyCreated) throw new BusinessException(Utils.getMessageProperty("sandboxInternal.cpfCnpjAlreadyExists"))
    }

    private void validateCustomerCommercialData(Customer customer) {
        if (!PhoneNumberUtils.validateMobilePhone(customer.mobilePhone)) throw getCommercialDataInvalidException()
        if (!CpfCnpjUtils.validate(customer.cpfCnpj)) throw getCommercialDataInvalidException()
    }

    private BusinessException getCommercialDataInvalidException() {
        return new BusinessException(Utils.getMessageProperty("sandboxInternal.commercialDataInvalid", [grailsLinkGenerator.link(controller: "config", action: "index", params: [tab: "information"])]))
    }

    private void deleteExternalIdentifier(ExternalIdentifier sandboxExternalIdentifier) {
        sandboxExternalIdentifier.deleted = true
        sandboxExternalIdentifier.save(failOnError: true, flush: true)
    }
}
