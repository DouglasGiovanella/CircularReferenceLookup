package com.asaas.service.integration.hubspot

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.hubspot.api.v3.HubspotManager
import com.asaas.integration.hubspot.dto.HubspotCreateDealRequestDTO
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class HubspotDealManagerService {

    public Boolean createDealAndContactAssociation(Map properties, String contactId) {
        if (!AsaasEnvironment.isProduction()) return true

        try {
            Map params = [:]
            params.properties = new HubspotCreateDealRequestDTO(properties).toMap()
            params.associations = buildDefaultAssociation(contactId)

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/crm/v3/objects/deals", params)

            if (!hubspotManager.isSuccessful()) {
                AsaasLogger.error("HubspotDealManagerService.createDeal >> Falha na requisição POST. ResponseBody: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")
            }

            return hubspotManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("HubspotDealManagerService.createDealAndContactAssociation >> Ocorreu um erro ao criar o negócio. Properties: [${properties}]. ContactId: [${contactId}]", e)
            return false
        }
    }

    private List<Map> buildDefaultAssociation(String contactId) {
        return [
            [
                to: [
                    id: contactId
                ],
                types: [
                    [
                        associationCategory: "HUBSPOT_DEFINED",
                        associationTypeId: "3"
                    ]
                ]
            ]
        ]
    }
}
