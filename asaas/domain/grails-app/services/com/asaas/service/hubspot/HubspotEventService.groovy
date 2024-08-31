package com.asaas.service.hubspot

import com.asaas.billinginfo.BillingType
import com.asaas.customeronboarding.CustomerOnboardingStepName
import com.asaas.domain.customer.Customer
import com.asaas.lead.LeadType
import com.asaas.log.AsaasLogger
import com.asaas.onboarding.AccountActivationOrigin
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingFunnelEventAdapter
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class HubspotEventService {

    private static final Integer FLUSH_EVERY = 50

    def asyncActionService
    def grailsApplication
    def hubspotEventManagerService
    def hubspotService

    public void trackCustomerFirstLoggedInDoublePromotionV2(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.customerFirstLoggedInDoublePromotionV2.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasActivatedDoublePromotionV2(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.customerActivatedDoublePromotionV2.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackAsaasOpportunityLead(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.asaasOpportunity.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasDownloadedApp(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.hasDowloadedApp.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasRequestedEloCard(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.hasRequestedEloCard.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasPaymentReceived(Customer customer, BillingType type) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildPaymentReceivedEventDataMap(customer.id, grailsApplication.config.hubspot.hasPaymentReceived.eventName, type)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerIntegratedWithErp(Long customerId) {
        Customer customer = Customer.read(customerId)
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customerId, grailsApplication.config.hubspot.customerIntegratedWithErp.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerCreated(Customer customer, LeadType leadType) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildCustomerCreatedEventDataMap(customer.id, grailsApplication.config.hubspot.createAccount.eventName, leadType)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackLeadCreated(String contactId, LeadType leadType) {
        if (!hubspotService.canSendInfoToHubspot(contactId)) return

        Map asyncActionData = buildLeadCreatedEventDataMap(contactId, grailsApplication.config.hubspot.leadCreated.eventName, leadType)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasPaymentCreated(Customer customer) {
        trackCustomerHasPaymentCreated(customer, null)
    }

    public void trackCustomerHasPaymentCreated(Customer customer, BillingType type) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData

        if (type) {
            asyncActionData = buildPaymentCreatedEventDataMap(customer.id, grailsApplication.config.hubspot.hasPaymentCreated.eventName, type)
            asyncActionService.saveSendEventToHubspot(asyncActionData)
            return
        }

        asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.hasPaymentCreated.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerHasCreatedApiKey(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.createdApiKey.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerAutomaticAnticipationAction(Customer customer, Boolean automaticAnticipationEnabled) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildAutomaticAnticipationEventDataMap(customer.id, grailsApplication.config.hubspot.automaticAnticipation.eventName, automaticAnticipationEnabled)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerAnticipationSimulatorActions(Customer customer, String action) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildAnticipationSimulatorEventDataMap(customer.id, grailsApplication.config.hubspot.anticipationSimulator.eventName, action)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerCreateAccountInSandboxByIntegrationsPage(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.createAccountInSandboxByIntegrationsPage.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerRequestAnticipationInPaymentList(Customer customer, String billingType) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildRequestAnticipationInPaymentListEventDataMap(customer.id, grailsApplication.config.hubspot.requestAnticipationPaymentList.eventName, billingType)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerRequestAnticipationInPaymentDetail(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.requestAnticipationPaymentDetail.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerRequestIndividualAnticipation(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.requestIndividualAnticipation.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCustomerRequestAnticipationSimulation(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.requestAnticipationSimulation.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackAnticipationAnalyzed(Customer customer, BillingType billingType, Boolean approved) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildAnticipationAnalyzedEventDataMap(customer.id, grailsApplication.config.hubspot.anticipationAnalyzed.eventName, billingType, approved)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackGeneralApprovalStatusChanged(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String conversionIdentifier = customer.customerRegisterStatus.generalApproval.isApproved() ? grailsApplication.config.hubspot.accountStatusApproved.eventName : grailsApplication.config.hubspot.accountStatusRejected.eventName

        Map asyncActionData = buildDefaultEventDataMap(customer.id, conversionIdentifier)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackOnboardingFinished(Customer customer) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        Map asyncActionData = buildDefaultEventDataMap(customer.id, grailsApplication.config.hubspot.finishedOnboarding.eventName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackOnboardingStepFinished(Customer customer, CustomerOnboardingStepName customerOnboardingStepName) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String eventName = grailsApplication.config.hubspot.finishedOnboardingStep.eventName
        Map asyncActionData = buildOnboardingStepFinishedEventDataMap(customer.id, eventName, customerOnboardingStepName)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackCanAnticipate(Customer customer, BillingType billingType) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String eventName = grailsApplication.config.hubspot.canAnticipate.eventName
        Map asyncActionData = buildAnticipateEventDataMap(customer.id, eventName, billingType)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackFirstTransferReachedMinimumPeriodToAnticipate(Customer customer, BillingType billingType) {
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String eventName = grailsApplication.config.hubspot.firstTransferReachedMinimumPeriodToAnticipate.eventName
        Map asyncActionData = buildAnticipateEventDataMap(customer.id, eventName, billingType)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackAccountActivated(Customer customer, AccountActivationOrigin accountActivationOrigin) {
        if (!accountActivationOrigin) {
            AsaasLogger.info("HubspotEventService.trackAccountActivated >> Origem da ativação não informada, track não enviado. CustomerId:[${customer.id}]")
            return
        }
        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String eventName = grailsApplication.config.hubspot.accountActivated.eventName
        Map asyncActionData = buildAccountActivatedEventDataMap(customer.id, eventName, accountActivationOrigin)
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void trackThirdPartyDocumentationOnboardingFunnel(ThirdPartyDocumentationOnboardingFunnelEventAdapter funnelEventAdapter) {
        Long customerId = funnelEventAdapter.customerId
        Long thirdPartyOnboardingId = funnelEventAdapter.thirdPartyOnboardingId
        Customer customer = Customer.read(customerId)
        if (!customer) {
            AsaasLogger.warn("HubspotEventService.trackThirdPartyDocumentationOnboardingFunnel >> Customer não encontrado CustomerId:[${customerId}], ThirdPartyOnboardingId:[${thirdPartyOnboardingId}]")
            return
        }
        if (!funnelEventAdapter.step) {
            AsaasLogger.warn("HubspotEventService.trackThirdPartyDocumentationOnboardingFunnel >> Step não informada CustomerId:[${customerId}], ThirdPartyOnboardingId:[${thirdPartyOnboardingId}]")
            return
        }

        if (!hubspotService.canSendInfoToHubspot(customer)) return

        String eventName = grailsApplication.config.hubspot.thirdPartyDocumentationOnboardingFunnel.eventName
        Map asyncActionData = buildThirdPartyDocumentationOnboardingFunnelEventDataMap(customerId, eventName, thirdPartyOnboardingId, funnelEventAdapter.step.toString())
        asyncActionService.saveSendEventToHubspot(asyncActionData)
    }

    public void processPendingSendEvent() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingSendEventToHubspot(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                Customer customer
                if (asyncActionData.customerId) {
                    customer = Customer.read(Utils.toLong(asyncActionData.customerId))
                }

                Map properties = [:]
                switch (asyncActionData.eventName) {
                    case grailsApplication.config.hubspot.createAccount.eventName:
                        properties = buildCreateAccountEventProperties(customer, asyncActionData)
                        break
                    case grailsApplication.config.hubspot.leadCreated.eventName:
                        properties = buildLeadCreatedEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.hasPaymentCreated.eventName:
                        properties = buildPaymentCreatedEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.hasPaymentReceived.eventName:
                        properties = buildPaymentReceivedEventProperties(BillingType.convert(asyncActionData.billingType))
                        break
                    case grailsApplication.config.hubspot.finishedOnboardingStep.eventName:
                        properties = buildOnboardingStepFinishedEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.canAnticipate.eventName:
                    case grailsApplication.config.hubspot.firstTransferReachedMinimumPeriodToAnticipate.eventName:
                        properties = buildAnticipateEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.automaticAnticipation.eventName:
                        properties = buildAutomaticAnticipationEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.anticipationSimulator.eventName:
                        properties = buildAnticipationSimulatorEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.anticipationAnalyzed.eventName:
                        properties = buildAnticipationAnalyzedEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.accountActivated.eventName:
                        properties = buildAccountActivatedEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.thirdPartyDocumentationOnboardingFunnel.eventName:
                        properties = buildThirdPartyDocumentationOnboardingFunnelEventProperties(asyncActionData)
                        break
                    case grailsApplication.config.hubspot.requestAnticipationPaymentList.eventName:
                        properties = buildRequestAnticipationInPaymentListEventProperties(asyncActionData)
                        break
                }

                Date eventDateFormatted = CustomDateUtils.fromString(asyncActionData?.eventDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)
                if (eventDateFormatted) properties += buildDefaultEventProperties(eventDateFormatted)

                Boolean success = false
                if (asyncActionData.contactId) {
                    success = hubspotEventManagerService.sendEventByContactId(asyncActionData.contactId, asyncActionData.eventName, properties)
                } else if (customer) {
                    success = hubspotEventManagerService.sendEventByCustomerId(customer.id, asyncActionData.eventName, properties)
                }

                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "HubspotEventService.processPendingSendEvent() >> Erro ao enviar evento [${asyncActionData.eventName}] ao hubspot. Email: [${asyncActionData.email}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    private Map buildDefaultEventProperties(Date eventDate) {
        Long timestampDate = eventDate.getTime()
        Map eventProperties = [:]
        eventProperties.event_date = timestampDate

        return eventProperties
    }

    private Map buildPaymentCreatedEventProperties(Map asyncActionData) {
        Map eventProperties = [:]
        if (asyncActionData.billingType) eventProperties.billing_type = asyncActionData.billingType

        return eventProperties
    }

    private Map buildPaymentReceivedEventProperties(BillingType type) {
        Map eventProperties = [:]
        eventProperties.billing_type = type

        return eventProperties
    }

    private Map buildLeadCreatedEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        if (asyncAction.leadType) eventProperties.lead_type = asyncAction.leadType

        return eventProperties
    }

    private Map buildCreateAccountEventProperties(Customer customer, Map asyncAction) {
        Map eventProperties = [:]
        String entryPromotionVariantValue = AbTestUtils.getEntryPromotionVariantForCustomer(customer)

        if (entryPromotionVariantValue) eventProperties.entry_promotion_v1_2024_ab_test_variant_value = entryPromotionVariantValue
        if (asyncAction.leadType) eventProperties.lead_type = asyncAction.leadType

        return eventProperties
    }

    private Map buildOnboardingStepFinishedEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.step_name = asyncAction.customerOnboardingStepName

        return eventProperties
    }

    private Map buildAnticipateEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.billing_type = asyncAction.billingType

        return eventProperties
    }

    private Map buildAutomaticAnticipationEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.automatic_anticipation_enabled = asyncAction.automaticAnticipationEnabled

        return eventProperties
    }

    private Map buildAnticipationSimulatorEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.action = asyncAction.action

        return eventProperties
    }

    private Map buildAnticipationAnalyzedEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.billing_type = asyncAction.billingType
        eventProperties.approved = asyncAction.approved

        return eventProperties
    }

    private Map buildRequestAnticipationInPaymentListEventProperties(Map asyncAction) {
        Map eventProperties = [:]

        if (asyncAction.billingType) eventProperties.billing_type = asyncAction.billingType

        return eventProperties
    }

    private Map buildAccountActivatedEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.activation_origin = asyncAction.activationOrigin

        return eventProperties
    }

    private Map buildThirdPartyDocumentationOnboardingFunnelEventProperties(Map asyncAction) {
        Map eventProperties = [:]
        eventProperties.third_party_onboarding_id = asyncAction.thirdPartyOnboardingId
        eventProperties.step = asyncAction.step

        return eventProperties
    }

    private Map buildDefaultEventDataMap(Long customerId, String eventName) {
        return buildDefaultEventDataMap(customerId, eventName, new Date())
    }

    private Map buildDefaultEventDataMap(Long customerId, String eventName, Date eventDate) {
        Map asyncActionData = [:]
        asyncActionData.customerId = customerId
        asyncActionData += buildCommonEventPropsDataMap(eventName, eventDate)

        return asyncActionData
    }

    private Map buildDefaultEventDataMap(String contactId, String eventName) {
        Map asyncActionData = [:]
        asyncActionData.contactId = contactId
        asyncActionData += buildCommonEventPropsDataMap(eventName, new Date())

        return asyncActionData
    }

    private Map buildCommonEventPropsDataMap(String eventName, Date eventDate) {
        Map asyncActionData = [:]
        asyncActionData.eventName = eventName
        asyncActionData.eventDate = CustomDateUtils.fromDate(eventDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)

        return asyncActionData
    }

    private Map buildPaymentCreatedEventDataMap(Long customerId, String eventName, BillingType type) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.billingType = type.toResponseAPI()

        return asyncActionData
    }

    private Map buildPaymentReceivedEventDataMap(Long customerId, String eventName, BillingType type) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        if (type) asyncActionData.billingType = type.toResponseAPI()

        return asyncActionData
    }

    private Map buildLeadCreatedEventDataMap(String contactId, String eventName, LeadType leadType) {
        Map asyncActionData = buildDefaultEventDataMap(contactId, eventName)
        if (leadType) asyncActionData.leadType = leadType

        return asyncActionData
    }

    private Map buildOnboardingStepFinishedEventDataMap(Long customerId, String eventName, CustomerOnboardingStepName customerOnboardingStepName) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.customerOnboardingStepName = customerOnboardingStepName

        return asyncActionData
    }

    private Map buildAnticipateEventDataMap(Long customerId, String eventName, BillingType billingType) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.billingType = billingType.toResponseAPI()

        return asyncActionData
    }

    private Map buildCustomerCreatedEventDataMap(Long customerId, String eventName, LeadType leadType) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        if (leadType) asyncActionData.leadType = leadType

        return asyncActionData
    }

    private Map buildAutomaticAnticipationEventDataMap(Long customerId, String eventName, Boolean automaticAnticipationEnabled) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.automaticAnticipationEnabled = automaticAnticipationEnabled

        return asyncActionData
    }

    private Map buildAnticipationSimulatorEventDataMap(Long customerId, String eventName, String action) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.action = action

        return asyncActionData
    }

    private Map buildRequestAnticipationInPaymentListEventDataMap(Long customerId, String eventName, String billingType) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.billingType = billingType

        return asyncActionData
    }

    private Map buildAnticipationAnalyzedEventDataMap(Long customerId, String eventName, BillingType billingType, Boolean approved) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.billingType = billingType.toResponseAPI()
        asyncActionData.approved = approved

        return asyncActionData
    }

    private Map buildAccountActivatedEventDataMap(Long customerId, String eventName, AccountActivationOrigin activationOrigin) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.activationOrigin = activationOrigin

        return asyncActionData
    }

    private Map buildThirdPartyDocumentationOnboardingFunnelEventDataMap(Long customerId, String eventName, Long thirdPartyOnboardingId, String step) {
        Map asyncActionData = buildDefaultEventDataMap(customerId, eventName)
        asyncActionData.thirdPartyOnboardingId = thirdPartyOnboardingId
        asyncActionData.step = step

        return asyncActionData
    }
}
