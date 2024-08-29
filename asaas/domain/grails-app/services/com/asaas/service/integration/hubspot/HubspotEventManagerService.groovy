package com.asaas.service.integration.hubspot

import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.integration.hubspot.api.v3.HubspotManager
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class HubspotEventManagerService {

    def externalIdentifierService
    def hubspotContactManagerService
    def hubspotService

    public Boolean sendEventByCustomerId(Long customerId, String eventName, Map properties) {
        if (!AsaasEnvironment.isProduction()) return true

        String contactId = searchContactIdIfExternalIdentifierDoesNotExist(customerId)
        if (!contactId) {
            AsaasLogger.warn("HubspotEventManagerService.sendEventByCustomerId -> ContactId não encontrado. Customer: [${customerId}]. eventName: [${eventName}]")
            return true
        }

        return sendEventByContactId(contactId, eventName, properties)
    }

    public Boolean sendEventByContactId(String contactId, String eventName, Map properties) {
        if (!AsaasEnvironment.isProduction()) return true

        try {
            Map params = [
                eventName: eventName,
                properties: properties,
                objectType: "contacts",
                objectId: contactId
            ]

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/events/v3/send", params)

            if (!hubspotManager.isSuccessful()) AsaasLogger.error("HubspotEventManagerService.sendEventByContactId -> Falha na requisição POST. EventName: [${eventName}]. contactId [${contactId}]. Params: [${params}]. Status: [${hubspotManager.statusCode}]. Body: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")

            return hubspotManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("HubspotEventManagerService.sendEventByContactId -> Erro ao marcar o identificador ${eventName}. contactId: [${contactId}]", e)
            return false
        }
    }

    private String searchContactIdIfExternalIdentifierDoesNotExist(Long customerId) {
        String contactId = hubspotService.getHubspotContactId(customerId)
        if (contactId) return contactId

        Customer customer = Customer.read(customerId)
        contactId = hubspotContactManagerService.searchContact(customer.email)
        if (contactId) externalIdentifierService.save(customer, ExternalApplication.HUBSPOT, ExternalResource.CUSTOMER, contactId, customer)
        return contactId
    }
}
