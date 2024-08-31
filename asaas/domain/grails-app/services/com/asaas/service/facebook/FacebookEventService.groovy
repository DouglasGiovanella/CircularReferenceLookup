package com.asaas.service.facebook

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customer.Customer
import com.asaas.domain.lead.LeadData
import com.asaas.log.AsaasLogger
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FacebookEventService {

    private static final Integer FLUSH_EVERY = 25

    private static final String CREATE_LEAD_EVENT = "LeadAsaas"

    private static final String ACCOUNT_ACTIVATION_EVENT = "Cadastro ativado"

    private static final String COMPLETE_ONBOARDING_EVENT = "Onboarding Completo"

    private static final String SIMULATE_ANTICIPATION_EVENT = "Simular antecipação"

    private static final String FIRST_PAYMENT_CREATED_EVENT = "Gerar primeira cobrança"

    private static final String FIRST_PAYMENT_RECEIVED_EVENT = "Receber primeira cobrança"

    private static final String SEND_FORM_DATA_PAYMENTS_API_LANDING_PAGE = "API de pagamento - Envio de formulário"

    def asyncActionService
    def facebookEventManagerService


    public void saveCreateLeadEvent(Long customerId) {
        saveEvent(customerId, CREATE_LEAD_EVENT)
    }

    public void saveAccountActivationEvent(Long customerId) {
        saveEvent(customerId, ACCOUNT_ACTIVATION_EVENT)
    }

    public void saveCompleteOnboardingEvent(Long customerId) {
        saveEvent(customerId, COMPLETE_ONBOARDING_EVENT)
    }

    public void saveSimulateAnticipationEvent(Long customerId) {
        saveEvent(customerId, SIMULATE_ANTICIPATION_EVENT)
    }

    public void saveFirstPaymentCreatedEvent(Long customerId) {
        asyncActionService.saveFacebookSendEvent([customerId: customerId, eventName: FIRST_PAYMENT_CREATED_EVENT, timestampDate: getCurrentTimestamp()])
    }

    public void saveFirstPaymentReceivedEvent(Long customerId) {
        asyncActionService.saveFacebookSendEvent([customerId: customerId, eventName: FIRST_PAYMENT_RECEIVED_EVENT, timestampDate: getCurrentTimestamp()])
    }

    public void saveSendFormDataPaymentsApiLandingPage(String distinctId, String email) {
        Map properties = getRequestData()
        properties.distinctId = distinctId
        properties.email = email
        properties.eventName = SEND_FORM_DATA_PAYMENTS_API_LANDING_PAGE
        properties.timestampDate = getCurrentTimestamp()
        asyncActionService.saveFacebookSendEvent(properties)
    }

    public void processSendEvent() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingFacebookSendEvent(maxPendingItems)

        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                String email
                String externalId

                if (asyncActionData.distinctId) {
                    externalId = asyncActionData.distinctId
                    email = asyncActionData.email
                } else {
                    Customer customer = Customer.read(asyncActionData.customerId)
                    if (!customer) {
                        AsaasLogger.error("FacebookEventService.processSendEvent() >> Nao foi encontrado um customer para o id: [${asyncActionData.customerId}]")
                        asyncActionService.setAsCancelled(asyncActionData.asyncActionId)
                        return
                    }

                    LeadData leadData = LeadData.query([customer: customer]).get()

                    if (!leadData.distinctId) {
                        leadData.distinctId = UUID.randomUUID().toString()
                        leadData.save(failOnError: true)
                    }

                    externalId = leadData.distinctId

                    email = customer.email
                }

                Boolean success = facebookEventManagerService.sendEvent(email, asyncActionData.eventName, externalId, asyncActionData)

                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "FacebookEventService.processSendEvent() >> Erro ao enviar o evento [${asyncActionData.eventName}] CustomerId: [${asyncActionData.customerId}] DistinctId: [${asyncActionData.distinctId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    private void saveEvent(Long customerId, String eventName) {
        Map properties = getDefaultProperties(customerId, eventName)
        asyncActionService.saveFacebookSendEvent(properties)
    }

    private Map getDefaultProperties(Long customerId, String eventName) {
        Map properties = [:]

        properties.customerId = customerId
        properties.eventName = eventName

        properties += getRequestData()

        return properties
    }

    private Map getRequestData() {
        Map properties = [:]

        properties.eventSourceUrl = "${AsaasApplicationHolder.grailsApplication.config.grails.serverURL}${RequestUtils.getForwardURI()}"
        properties.fbc = RequestUtils.getCookieValue("_fbc")
        properties.fbp = RequestUtils.getCookieValue("_fbp")
        properties.requestIp = RequestUtils.getRemoteIp()
        properties.userAgent = AsaasApplicationHolder.grailsApplication.mainContext.userAgentIdentService.getUserAgentString()
        properties.timestampDate = getCurrentTimestamp()

        return properties
    }

    private Long getCurrentTimestamp() {
        TimeZone gmtTimeZone = TimeZone.getTimeZone("UTC")
        Date nowUtc = Calendar.getInstance(gmtTimeZone).getTime()
        Long timestampDate = nowUtc.getTime() / 1000

        return timestampDate
    }
}
