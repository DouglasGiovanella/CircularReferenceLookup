package com.asaas.service.integration.hubspot

import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.environment.AsaasEnvironment
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.integration.hubspot.adapter.HubspotContactAdapter
import com.asaas.integration.hubspot.api.v3.HubspotManager
import com.asaas.integration.hubspot.dto.HubspotContactDTO
import com.asaas.integration.hubspot.dto.HubspotCreateContactResponseDTO
import com.asaas.integration.hubspot.enums.HubspotGrowthStatus
import com.asaas.integration.hubspot.enums.UpdateHubspotContactType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class HubspotContactManagerService {

    public static final String MESSAGE_CONTACT_ALREADY_EXISTS = "Contact already exists"

    def externalIdentifierService
    def grailsApplication
    def hubspotEventService
    def hubspotService

    public String createContact(String email, HubspotGrowthStatus growthStatus) {
        if (!AsaasEnvironment.isProduction()) return null

        try {
            Map params = [
                properties: [
                    email: email,
                    growth_status: growthStatus
                ]
            ]

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/crm/v3/objects/contacts", params)

            if (!hubspotManager.isSuccessful()) {
                String errorMessage = hubspotManager.responseBody?.message
                handleCreateContactError(errorMessage, params)
                return null
            }

            return hubspotManager.responseBody.id
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.createContact -> Ocorreu um erro ao criar o contato. Email: [${email}].", e)
            return null
        }
    }

    public HubspotContactAdapter createContact(Customer customer, HubspotGrowthStatus growthStatus, List<Map> leadUtmPropsList) {
        if (!AsaasEnvironment.isProduction()) return new HubspotContactAdapter(new MockJsonUtils("hubspot/HubspotContactManagerService/createContact.json").buildMock(HubspotCreateContactResponseDTO))

        try {
            Map params = [
                properties: buildContactProperties(customer, growthStatus, leadUtmPropsList)
            ]

            AsaasLogger.info("HubspotContactManagerService.createContact -> Lead [${params.properties.email}]")

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/crm/v3/objects/contacts", params)

            if (!hubspotManager.isSuccessful()) {
                String errorMessage = hubspotManager.responseBody?.message
                return new HubspotContactAdapter(handleCreateContactError(errorMessage, params))
            }

            HubspotCreateContactResponseDTO hubspotCreateContactResponseDTO = GsonBuilderUtils.buildClassFromJson((hubspotManager.responseBody + [success: hubspotManager.isSuccessful()] as JSON).toString(), HubspotCreateContactResponseDTO)
            return new HubspotContactAdapter(hubspotCreateContactResponseDTO)
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.createContact -> Ocorreu um erro ao criar o contato. Email: [${customer.email}]. CustomerId: [${customer.id}]", e)
            HubspotCreateContactResponseDTO hubspotCreateContactResponseDTO = GsonBuilderUtils.buildClassFromJson(([id: "", success: false] as JSON).toString(), HubspotCreateContactResponseDTO)
            return new HubspotContactAdapter(hubspotCreateContactResponseDTO)
        }
    }

    public Boolean unsubscribe(String email) {
        if (!AsaasEnvironment.isProduction()) return true

        try {
            Map params = [
                emailAddress: email,
                subscriptionId: grailsApplication.config.hubspot.subscriptionId,
                legalBasis: "PROCESS_AND_STORE",
                legalBasisExplanation: "Not applicable"
            ]

            AsaasLogger.info("HubspotContactManagerService.unsubscribe -> Email [${email}].")

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/communication-preferences/v3/unsubscribe", params)

            if (!hubspotManager.isSuccessful()) AsaasLogger.error("HubspotContactManagerService.unsubscribe -> Falha na requisição POST. Email: [${email}]. Params: [${params}]. Status: [${hubspotManager.statusCode}]. Body: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")

            return hubspotManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.unsubscribe -> Ocorreu um erro ao desabilitar recebimento de emails. Email: ${email}", e)
            return false
        }
    }

    public String searchContact(String email) {
        if (!AsaasEnvironment.isProduction()) return null

        try {
            Map params = [
                filterGroups: [
                    [
                        filters: [
                            [
                                operator: "EQ",
                                propertyName: "email",
                                value: email
                            ]
                        ]
                    ]
                ]
            ]

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.post("/crm/v3/objects/contacts/search", params)

            if (!hubspotManager.isSuccessful()) {
                AsaasLogger.error("HubspotContactManagerService.searchContact -> Falha na requisição POST. Email: [${email}]. Status: [${hubspotManager.statusCode}]. Body: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")
            }

            if (hubspotManager.responseBody?.results?.size() > 0) return hubspotManager.responseBody.results[0].id

            return null
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.searchContact -> Ocorreu um erro ao procurar contato. Email: ${email}", e)
            return null
        }
    }

    public Boolean updateContact(Customer customer, UpdateHubspotContactType contactType, HubspotGrowthStatus growthStatus) {
        if (!AsaasEnvironment.isProduction()) return true

        String contactId = searchContactIdIfExternalIdentifierDoesNotExist(customer)

        if (!contactId) {
            AsaasLogger.warn("HubspotContactManagerService.updateContact -> ContactId não encontrado. Customer: [${customer.id}]. type: [${contactType}]")
            return true
        }

        try {
            Map properties = buildContactProperties(customer, contactType, growthStatus)

            if (!properties) {
                AsaasLogger.error("HubspotContactManagerService.updateContact -> O map está vazio. Customer: [${customer.id}]. Type: [${contactType}]")
                return false
            }

            Map params = [
                properties: properties
            ]

            if (growthStatus?.isInactive()) params.properties["person_type"] = customer.personType?.toString()

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.patch("/crm/v3/objects/contacts/${contactId}", params)

            if (!hubspotManager.isSuccessful()) {
                if (hubspotManager.statusCode == HttpStatus.CONFLICT.value()) {
                    AsaasLogger.info("HubspotContactManagerService.updateContact -> Não foi possível atualizar, contato já existente. customerId: [${customer.id}].")
                    return false
                } else {
                    AsaasLogger.error("HubspotContactManagerService.updateContact -> Falha na requisição PATCH. Customer: [${customer.id}]. Params: [${params}]. Status: [${hubspotManager.statusCode}]. Body: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")
                    return false
                }
            }

            if (contactType == UpdateHubspotContactType.STATUS) hubspotEventService.trackGeneralApprovalStatusChanged(customer)

            return hubspotManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.updateContact -> Ocorreu um erro ao atualizar o contato. Customer: [${customer.id}]", e)
            return false
        }
    }

    public Boolean updateContactWithMissingDefaultProps(Customer customer, String contactExternalId, HubspotGrowthStatus growthStatus, List<Map> leadUtmPropsList) {
        if (!AsaasEnvironment.isProduction()) return true

        try {
            Map params = [
                properties: buildContactProperties(customer, growthStatus, leadUtmPropsList)
            ]

            HubspotManager hubspotManager = new HubspotManager()
            hubspotManager.patch("/crm/v3/objects/contacts/${contactExternalId}", params)

            if (!hubspotManager.isSuccessful()) {
                AsaasLogger.error("HubspotContactManagerService.updateContactWithMissingDefaultProps -> Falha na requisição PATCH. Customer: [${customer.id}]. Params: [${params}]. Status: [${hubspotManager.statusCode}]. Body: [${hubspotManager.responseBody}]. ErrorMessage: [${hubspotManager.errorMessage}]")
                return false
            }

            return hubspotManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("HubspotContactManagerService.updateContactWithMissingDefaultProps -> Ocorreu um erro ao atualizar o contato. Customer: [${customer.id}]", e)
            return false
        }
    }

    private String searchContactIdIfExternalIdentifierDoesNotExist(Customer customer) {
        String contactId = hubspotService.getHubspotContactId(customer.id)
        if (contactId) return contactId

        contactId = searchContact(customer.email)
        if (contactId) externalIdentifierService.save(customer, ExternalApplication.HUBSPOT, ExternalResource.CUSTOMER, contactId, customer)
        return contactId
    }

    private Map buildContactProperties(Customer customer, UpdateHubspotContactType contactType, HubspotGrowthStatus growthStatus) {
        switch (contactType) {
            case UpdateHubspotContactType.COMMERCIAL_INFO:
                HubspotContactDTO hubspotContactDTO = new HubspotContactDTO(customer)
                return hubspotContactDTO.toMap()
            case UpdateHubspotContactType.STATUS:
                Map contactProps = [:]
                contactProps["account_status"] = customer.customerRegisterStatus.generalApproval.toString()
                return contactProps
            case UpdateHubspotContactType.GROWTH_STATUS:
                Map contactProps = [:]
                contactProps["growth_status"] = growthStatus
                return contactProps
            default:
                Map contactProps = [:]
                return contactProps
        }
    }

    private Map buildContactProperties(Customer customer, HubspotGrowthStatus growthStatus, List<Map> leadUtmPropsList) {
        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: 'originChannel', customer: customer]).get()
        String email = customer.email
        String accountManagerEmail = customer.accountManager.email
        String accountManagerName = customer.accountManager.name
        String customerDateCreated = CustomDateUtils.fromDateWithTime(customer.dateCreated)

        Map props = [:]
        props.email = email
        props.account_manager_email = accountManagerEmail
        props.account_manager_name = accountManagerName
        props.account_creation_date = customerDateCreated
        props.lead_origin = customerSignUpOriginChannel
        props.growth_status = growthStatus
        if (growthStatus?.isInactive()) props.person_type = customer.personType?.toString()

        if (!leadUtmPropsList) return props
        Map firstLeadUtmProps = leadUtmPropsList.find({ it.sequence.isFirst() })
        Map lastLeadUtmProps = leadUtmPropsList.find({ it.sequence.isLast() })

        props.first_referrer = firstLeadUtmProps.referrer
        props.last_referrer = lastLeadUtmProps.referrer
        props.first_utm_source = firstLeadUtmProps.source
        props.last_utm_source = lastLeadUtmProps.source
        props.first_utm_medium = firstLeadUtmProps.medium
        props.last_utm_medium = lastLeadUtmProps.medium
        props.first_utm_campaign = firstLeadUtmProps.campaign
        props.last_utm_campaign = lastLeadUtmProps.campaign
        props.first_utm_term = firstLeadUtmProps.term
        props.last_utm_term = lastLeadUtmProps.term
        props.first_utm_content = firstLeadUtmProps.content
        props.last_utm_content = lastLeadUtmProps.content
        props.first_gclid = firstLeadUtmProps.gclid
        props.last_gclid = lastLeadUtmProps.gclid
        props.first_fbclid = firstLeadUtmProps.fbclid
        props.last_fbclid = lastLeadUtmProps.fbclid

        return props
    }

    private HubspotCreateContactResponseDTO handleCreateContactError(String errorMessage, Map params) {
        if (errorMessage?.contains("INVALID_EMAIL")) {
            return GsonBuilderUtils.buildClassFromJson(([id: "", success: false] as JSON).toString(), HubspotCreateContactResponseDTO)
        }

        if (errorMessage?.contains(MESSAGE_CONTACT_ALREADY_EXISTS)) {
            String contactId = errorMessage.split(": ")[1]
            if (!contactId) {
                AsaasLogger.info("HubspotContactManagerService.createContact -> ContactId não encontrado na mensagem de resposta. ErrorMessage: [${errorMessage}]. Params: [${params}].")
                return GsonBuilderUtils.buildClassFromJson(([id: "", success: false] as JSON).toString(), HubspotCreateContactResponseDTO)
            }

            return GsonBuilderUtils.buildClassFromJson(([id: contactId, success: true, updateContact: true] as JSON).toString(), HubspotCreateContactResponseDTO)
        }

        AsaasLogger.error("HubspotContactManagerService.createContact -> Falha na requisição POST.  ErrorMessage: [${errorMessage}]. Params: [${params}].")
        return GsonBuilderUtils.buildClassFromJson(([id: "", success: false] as JSON).toString(), HubspotCreateContactResponseDTO)
    }
}
