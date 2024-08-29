package com.asaas.service.customerlostrecovery

import com.asaas.customer.PersonType
import com.asaas.customerlostrecovery.CustomerLostRecoveryCampaign
import com.asaas.customerlostrecovery.CustomerLostRecoveryEmail
import com.asaas.customerlostrecovery.CustomerLostRecoveryStage
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerlostrecovery.CustomerLostRecovery
import com.asaas.integration.hubspot.enums.HubspotGrowthStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomerLostRecoveryUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerLostRecoveryCampaignHubspotService {

    private static final Integer FLUSH_EVERY = 50

    def asyncActionService
    def customerLostRecoveryService
    def hubspotService

    public void createOrUpdateIfExistsInactiveContact(Long initialValue, Long finalValue) {
        List<Long> customerIdList = listInactiveContacts(initialValue, finalValue)

        AsaasLogger.info("CustomerLostRecoveryCampaignHubspotService.createOrUpdateIfExistsInactiveContact >> Encontrado ${customerIdList.size()} contatos para criar e/ou atualizar no hubspot como inativos. Intervalo: [${initialValue}, ${finalValue}]")

        Utils.forEachWithFlushSession(customerIdList, FLUSH_EVERY, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                String contactId = hubspotService.getHubspotContactId(customer.id)

                if (contactId) {
                    asyncActionService.saveUpdateHubspotGrowthStatus(customerId, HubspotGrowthStatus.INACTIVE)
                } else {
                    asyncActionService.saveCreateHubspotContact(customerId, HubspotGrowthStatus.INACTIVE, null)
                }

                CustomerLostRecoveryStage stage
                switch (customer.personType) {
                    case PersonType.JURIDICA:
                        stage = CustomerLostRecoveryStage.LEGAL_PERSON
                        break
                    case PersonType.FISICA:
                        stage = CustomerLostRecoveryStage.NATURAL_PERSON
                        break
                    default:
                        stage = CustomerLostRecoveryStage.ACCOUNT_WITHOUT_CUSTOMER_INFO
                }

                customerLostRecoveryService.processCustomerLostRecoveryCreation(customer, stage, CustomerLostRecoveryEmail.HUBSPOT_PROMOTION_EMAIL, CustomerLostRecoveryCampaign.HUBSPOT_FIRST_FLOW_ATTEMPT, [:])
            }, [logErrorMessage: "CustomerLostRecoveryCampaignHubspotService.createOrUpdateIfExistsInactiveContact >> Erro ao criar e/ou atualizar contato inativo. CustomerId: [${customerId}]"])
        })
    }

    private List<Long> listInactiveContacts(Long initialValue, Long finalValue) {
        List<Long> customerIdList = Customer.createCriteria().list() {
            with(CustomerLostRecoveryUtils.defaultFiltersForHubspotCampaign(initialValue, finalValue))

            notExists CustomerLostRecovery.where {
                setAlias("clr")
                eqProperty("clr.customer.id", "this.id")
                eq("clr.deleted", false)
                eq("clr.campaign", CustomerLostRecoveryCampaign.HUBSPOT_FIRST_FLOW_ATTEMPT)
            }.id()
        }

        return customerIdList
    }
}
