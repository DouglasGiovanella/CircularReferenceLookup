package com.asaas.service.hubspot

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginPlatform
import com.asaas.customer.CustomerStatus
import com.asaas.customerlostrecovery.CustomerLostRecoveryCampaign
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.job.JobConfig
import com.asaas.domain.lead.LeadUtm
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.integration.hubspot.adapter.HubspotContactAdapter
import com.asaas.integration.hubspot.enums.HubspotGrowthStatus
import com.asaas.integration.hubspot.enums.UpdateHubspotContactType
import com.asaas.lead.LeadType
import com.asaas.log.AsaasLogger
import com.asaas.unsubscribeEmail.UnsubscribedEmailSource
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class HubspotContactService {

    private static final String ACTIVE_JOB_NAME = "ActiveHubspotContactJob"

    private static final Integer FLUSH_EVERY = 50

    private static final Integer MINUTES_TO_CHECK_EXECUTION_JOB = -30

    def asyncActionService
    def customerLostRecoveryService
    def externalIdentifierService
    def hubspotContactManagerService
    def hubspotEventService
    def hubspotService
    def sessionFactory

    public void createUpdateHubspotGrowthStatus() {
        List<BigInteger> customerIdList = listCustomerWhoCreatedPayment()

        Utils.forEachWithFlushSession(customerIdList, 50, { BigInteger customerId ->
            Utils.withNewTransactionAndRollbackOnError ({
                Customer customer = Customer.read(customerId)
                hubspotEventService.trackCustomerHasPaymentCreated(customer)

                asyncActionService.saveUpdateHubspotGrowthStatus(customerId.toLong(), HubspotGrowthStatus.ACTIVE)
            }, [logErrorMessage: "HubspotContactService.createUpdateHubspotGrowthStatus >> Erro ao criar atualização de status do contato do cliente [id: ${customerId}]."] )
        })
    }

    public processPendingCreateContact() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingCreateHubspotContact(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                if (!asyncActionData.customerId) {
                    if (!asyncActionData.email || !Utils.emailIsValid(asyncActionData.email)) {
                        asyncActionService.delete(asyncActionData.asyncActionId)
                        return
                    }

                    String hubspotContactExternalId = hubspotContactManagerService.searchContact(asyncActionData.email)
                    if (hubspotContactExternalId) {
                        hubspotEventService.trackLeadCreated(hubspotContactExternalId, LeadType.convert(asyncActionData.leadType))
                        asyncActionService.delete(asyncActionData.asyncActionId)
                        return
                    }

                    String contactId = hubspotContactManagerService.createContact(asyncActionData.email, HubspotGrowthStatus.valueOf(asyncActionData.growthStatus))
                    if (Utils.isEmptyOrNull(contactId)) {
                        asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        return
                    }

                    hubspotEventService.trackLeadCreated(contactId, LeadType.convert(asyncActionData.leadType))
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                Customer customer = Customer.read(asyncActionData.customerId)
                HubspotGrowthStatus growthStatus = HubspotGrowthStatus.ACCOUNT_CREATED
                if (asyncActionData.growthStatus) growthStatus = HubspotGrowthStatus.valueOf(asyncActionData.growthStatus)

                List<Map> leadUtmPropsList = LeadUtm.query([
                    customerId: customer.id,
                    columnList: ["sequence", "source", "medium", "campaign", "term", "content", "gclid", "fbclid", "referrer"]
                ]).list()

                String hubspotContactExternalId = hubspotContactManagerService.searchContact(customer.email)
                if (hubspotContactExternalId) {
                    Boolean success = hubspotContactManagerService.updateContactWithMissingDefaultProps(customer, hubspotContactExternalId, growthStatus, leadUtmPropsList)
                    if (!success) {
                        asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        return
                    }
                    updateAlreadyCreatedContactOutsideTheIntegration(customer.id)
                } else {
                    HubspotContactAdapter hubspotContactAdapter = hubspotContactManagerService.createContact(customer, growthStatus, leadUtmPropsList)
                    hubspotContactExternalId = hubspotContactAdapter.id
                    if (!hubspotContactAdapter.success) {
                        asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        return
                    }
                    if (hubspotContactAdapter.updateContact) updateAlreadyCreatedContactOutsideTheIntegration(customer.id)
                }

                externalIdentifierService.save(customer, ExternalApplication.HUBSPOT, ExternalResource.CUSTOMER, hubspotContactExternalId, customer)

                hubspotEventService.trackCustomerCreated(customer, LeadType.convert(asyncActionData.leadType))

                Boolean createdAccountByMobile = CustomerSignUpOrigin.query([exists: true, customer: customer, "originPlatform[in]": CustomerSignUpOriginPlatform.getMobilePlatformList()]).get().asBoolean()
                if (createdAccountByMobile) hubspotEventService.trackCustomerHasDownloadedApp(customer)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "HubspotContactService.processPendingCreateContact() >> Erro ao criar o contato. Email: [${asyncActionData.email}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    public void processPendingUpdateContact() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingUpdateHubspotContact(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                Customer customer = Customer.read(asyncActionData.customerId)

                UpdateHubspotContactType contactType = UpdateHubspotContactType.valueOf(asyncActionData.type)

                HubspotGrowthStatus growthStatus = HubspotGrowthStatus.convert(asyncActionData.growthStatus)

                if (contactType.isGrowthStatus() && growthStatus?.isActive()) customerLostRecoveryService.convertLostCustomerIfNecessary(customer.id, [CustomerLostRecoveryCampaign.HUBSPOT_FIRST_FLOW_ATTEMPT])

                Boolean success = hubspotContactManagerService.updateContact(customer, contactType, growthStatus)

                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "HubspotContactService.processPendingUpdateContact() >> Erro ao atualizar o contato. CustomerId: [${asyncActionData.customerId}]. Type: [${asyncActionData.type}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    public void processPendingUnsubscribeContact() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingUnsubscribeHubspotContact(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                Boolean success = hubspotContactManagerService.unsubscribe(asyncActionData.email)

                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "HubspotContactService.processPendingUnsubscribeContact() >> Erro ao realizar desinscrição. Email: [${asyncActionData.email}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    public void saveContactCreation(Customer customer, LeadType leadType) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        asyncActionService.saveCreateHubspotContact(customer.id, HubspotGrowthStatus.ACCOUNT_CREATED, leadType)
    }

    public void saveLeadCreateHubspotContact(String email, LeadType leadType) {
        asyncActionService.saveLeadCreateHubspotContact(email, HubspotGrowthStatus.LEAD, leadType)
    }

    public void saveCommercialInfoUpdate(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        asyncActionService.saveUpdateHubspotCommercialInfo(customer.id)
    }

    public void saveContactStatusUpdate(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        asyncActionService.saveUpdateHubspotContactStatus(customer.id)
    }

    public void saveUnsubscribe(String email, UnsubscribedEmailSource source) {
        if (source == UnsubscribedEmailSource.ACCOUNT_DISABLED) return

        if (!hubspotService.hasContactId(email)) return

        asyncActionService.saveUnsubscribeHubspotContact(email)
    }

    public void saveUnsubscribe(String email) {
        if (!hubspotService.hasContactId(email)) return

        asyncActionService.saveUnsubscribeHubspotContact(email)
    }

    private void updateAlreadyCreatedContactOutsideTheIntegration(Long customerId) {
        asyncActionService.saveUpdateHubspotGrowthStatus(customerId, HubspotGrowthStatus.ACCOUNT_CREATED)
        asyncActionService.saveUpdateHubspotCommercialInfo(customerId)
    }

    private List<BigInteger> listCustomerWhoCreatedPayment() {
        Date startDate = JobConfig.query([job: ACTIVE_JOB_NAME, column: "finishDate"]).get()

        if (!startDate) {
            AsaasLogger.info("HubspotContactService.listCustomerWhoCreatedPayment() -> Data de ultima execução do Job não foi encontrada, utilizando horario de -30 minutos")
            startDate = CustomDateUtils.sumMinutes(new Date(), MINUTES_TO_CHECK_EXECUTION_JOB)
        }

        StringBuilder builder = new StringBuilder()

        builder.append("SELECT DISTINCT p.provider_id FROM payment p ")
        builder.append("    JOIN external_identifier ei ON ei.application = :application ")
        builder.append("        AND ei.object = :object ")
        builder.append("        AND ei.object_id = p.provider_id ")
        builder.append("        AND ei.resource = :resource ")
        builder.append("    JOIN customer c ON c.id = p.provider_id ")
        builder.append("    WHERE ")
        builder.append("        p.date_created >= :startDate ")
        builder.append("        AND p.deleted = :deleted ")
        builder.append("        AND ei.deleted = :deleted ")
        builder.append("        AND c.deleted = :deleted ")
        builder.append("        AND c.status not in (:inactiveStatus) ")
        builder.append("        AND c.account_owner_id IS NULL ")
        builder.append("        AND NOT EXISTS (SELECT 1 FROM customer_parameter cp ")
        builder.append("            WHERE cp.customer_id = ei.customer_id ")
        builder.append("                    AND cp.name in (:customerParameterList) ")
        builder.append("                    AND cp.value = :activeParameter) ")

        def query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setString("application", ExternalApplication.HUBSPOT.toString())

        query.setString("object", Customer.class.simpleName)

        query.setString("resource", ExternalResource.CUSTOMER.toString())

        query.setTimestamp("startDate", startDate)

        query.setBoolean("deleted", false)

        query.setParameterList("inactiveStatus", CustomerStatus.inactive().collect { it.toString() })

        query.setParameterList("customerParameterList", [CustomerParameterName.CHECKOUT_DISABLED.toString(), CustomerParameterName.WHITE_LABEL.toString()])
        query.setBoolean("activeParameter", true)

        List<BigInteger> customerIdList = query.list()

        return customerIdList
    }
}
