package com.asaas.service.campaigneventmessage

import com.asaas.campaignevent.CampaignEventName
import com.asaas.campaignevent.vo.CampaignEventAccountActivatedVO
import com.asaas.campaignevent.vo.CampaignEventAccountCreatedVO
import com.asaas.campaignevent.vo.CampaignEventAccountEmailChangedVO
import com.asaas.campaignevent.vo.CampaignEventCustomerPlanCanceledVO
import com.asaas.campaignevent.vo.CampaignEventCustomerPlanContractedVO
import com.asaas.campaignevent.vo.CampaignEventCustomerPlanOverduePaymentVO
import com.asaas.campaignevent.vo.CampaignEventCustomerPlanPaymentMethodChangedVO
import com.asaas.campaignevent.vo.CampaignEventLeadCreatedVO
import com.asaas.campaignevent.vo.CampaignEventMessageVO
import com.asaas.campaignevent.vo.CampaignEventOnboardingStepFinishedVO
import com.asaas.campaignevent.vo.CampaignEventThirdPartyDocumentationOnboardingFunnelVO
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.customeronboarding.CustomerOnboardingStepName
import com.asaas.customerplan.adapters.NotifyCanceledCustomerPlanAdapter
import com.asaas.customerplan.adapters.NotifyCustomerPlanPaymentMethodChangedAdapter
import com.asaas.customersignuporigin.adapter.CustomerSignUpOriginAdapter
import com.asaas.domain.campaignevent.CampaignEventMessage
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.lead.LeadData
import com.asaas.lead.adapter.LeadDataAdapter
import com.asaas.log.AsaasLogger
import com.asaas.onboarding.AccountActivationOrigin
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingFunnelEventAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.GsonBuilderUtils
import grails.transaction.Transactional

@Transactional
class CreateCampaignEventMessageService {

    public CampaignEventMessage save(CampaignEventName campaignEventName, Map data) {
        CampaignEventMessage validatedCampaignEventMessage = validateSave(campaignEventName, data)
        if (validatedCampaignEventMessage.hasErrors()) {
            AsaasLogger.error("CreateCampaignEventMessageService.save >> Falha ao validar parametros para salvar evento. EventName: [${campaignEventName}]. Data: [${data}]")
            return validatedCampaignEventMessage
        }

        CampaignEventMessage campaignEventMessage = new CampaignEventMessage()
        campaignEventMessage.eventName = campaignEventName
        campaignEventMessage.data = GsonBuilderUtils.toJsonWithoutNullFields(data)
        campaignEventMessage.save(failOnError: true)

        return campaignEventMessage
    }

    public CampaignEventMessage saveForCustomerPlanOverduePayment(Customer customer) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventCustomerPlanOverduePaymentVO campaignEventCustomerPlanOverduePaymentVO = new CampaignEventCustomerPlanOverduePaymentVO(customer)
        return save(CampaignEventName.CUSTOMER_PLAN_OVERDUE_PAYMENT, campaignEventCustomerPlanOverduePaymentVO.toMap())
    }

    public CampaignEventMessage saveForCustomerPlanPaymentMethodChanged(NotifyCustomerPlanPaymentMethodChangedAdapter adapter) {
        if (!canSendEvent(adapter.customer)) return new CampaignEventMessage()

        CampaignEventCustomerPlanPaymentMethodChangedVO campaignEventCustomerPlanPaymentMethodChangedVO = new CampaignEventCustomerPlanPaymentMethodChangedVO(adapter)
        return save(CampaignEventName.CUSTOMER_PLAN_PAYMENT_METHOD_CHANGED, campaignEventCustomerPlanPaymentMethodChangedVO.toMap())
    }

    public CampaignEventMessage saveForCustomerPlanContracted(Customer customer, String planName) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventCustomerPlanContractedVO campaignEventCustomerPlanContractedVO = new CampaignEventCustomerPlanContractedVO(customer, planName)
        return save(CampaignEventName.CUSTOMER_PLAN_CONTRACTED, campaignEventCustomerPlanContractedVO.toMap())
    }

    public CampaignEventMessage saveForCustomerPlanCanceled(NotifyCanceledCustomerPlanAdapter adapter) {
        if (!canSendEvent(adapter.customer)) return new CampaignEventMessage()

        CampaignEventCustomerPlanCanceledVO campaignEventCustomerPlanCanceledVO = new CampaignEventCustomerPlanCanceledVO(adapter)
        return save(CampaignEventName.CUSTOMER_PLAN_CANCELED, campaignEventCustomerPlanCanceledVO.toMap())
    }

    public CampaignEventMessage saveForAccountCreated(CustomerSignUpOriginAdapter adapter) {
        if (!canSendEvent(adapter.customer)) return new CampaignEventMessage()

        CampaignEventAccountCreatedVO campaignEventAccountCreatedVO = new CampaignEventAccountCreatedVO(adapter)
        return save(CampaignEventName.ACCOUNT_CREATED, campaignEventAccountCreatedVO.toMap())
    }

    public CampaignEventMessage saveForAccountEmailChanged(Customer customer, String previousEmail, String newEmail) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventAccountEmailChangedVO campaignEventAccountEmailChangedVO = new CampaignEventAccountEmailChangedVO(customer, previousEmail, newEmail)
        return save(CampaignEventName.ACCOUNT_EMAIL_CHANGED, campaignEventAccountEmailChangedVO.toMap())
    }

    public CampaignEventMessage saveForLeadCreated(LeadData leadData, LeadDataAdapter adapter) {
        CampaignEventLeadCreatedVO campaignEventLeadCreatedVO = new CampaignEventLeadCreatedVO(leadData, adapter)
        return save(CampaignEventName.LEAD_CREATED, campaignEventLeadCreatedVO.toMap())
    }

    public CampaignEventMessage saveForOnboardingFinished(Customer customer) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventMessageVO campaignEventMessageVO = new CampaignEventMessageVO(customer, CampaignEventName.ONBOARDING_FINISHED)
        return save(CampaignEventName.ONBOARDING_FINISHED, campaignEventMessageVO.toMap())
    }

    public CampaignEventMessage saveForOnboardingStepFinished(Customer customer, CustomerOnboardingStepName stepName) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventOnboardingStepFinishedVO campaignEventOnboardingStepFinishedVO = new CampaignEventOnboardingStepFinishedVO(customer, stepName)
        return save(CampaignEventName.ONBOARDING_STEP_FINISHED, campaignEventOnboardingStepFinishedVO.toMap())
    }

    public CampaignEventMessage saveForAccountActivated(Customer customer, AccountActivationOrigin accountActivationOrigin) {
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventAccountActivatedVO campaignEventAccountActivatedVO = new CampaignEventAccountActivatedVO(customer, accountActivationOrigin)
        return save(CampaignEventName.ACCOUNT_ACTIVATED, campaignEventAccountActivatedVO.toMap())
    }

    public CampaignEventMessage saveForCustomerIntegratedWithErp(Long customerId) {
        Customer customer = Customer.read(customerId)
        if (!canSendEvent(customer)) return new CampaignEventMessage()

        CampaignEventMessageVO campaignEventMessageVO = new CampaignEventMessageVO(customer, CampaignEventName.INTEGRATED_WITH_ERP)
        return save(CampaignEventName.INTEGRATED_WITH_ERP, campaignEventMessageVO.toMap())
    }

    public CampaignEventMessage saveForThirdPartyDocumentationOnboardingFunnel(ThirdPartyDocumentationOnboardingFunnelEventAdapter funnelEventAdapter) {
        Long customerId = funnelEventAdapter.customerId
        Long thirdPartyOnboardingId = funnelEventAdapter.thirdPartyOnboardingId
        Customer customer = Customer.read(customerId)
        if (!customer) {
            AsaasLogger.warn("CreateCampaignEventMessageService.saveForThirdPartyDocumentationOnboardingFunnel >> Customer não encontrado CustomerId:[${customerId}], ThirdPartyOnboardingId:[${thirdPartyOnboardingId}]")
            return
        }

        if (!funnelEventAdapter.step) {
            AsaasLogger.warn("CreateCampaignEventMessageService.saveForThirdPartyDocumentationOnboardingFunnel >> Step não informada CustomerId:[${customerId}], ThirdPartyOnboardingId:[${thirdPartyOnboardingId}]")
            return
        }

        if (!canSendEvent(customer)) return
        CampaignEventThirdPartyDocumentationOnboardingFunnelVO thirdPartyDocumentationOnboardingFunnelVO = new CampaignEventThirdPartyDocumentationOnboardingFunnelVO(customer, thirdPartyOnboardingId, funnelEventAdapter.step.toString())
        return save(CampaignEventName.THIRD_PARTY_DOCUMENTATION_ONBOARDING_FUNNEL, thirdPartyDocumentationOnboardingFunnelVO.toMap())
    }

    private Boolean canSendEvent(Customer customer) {
        if (customer.accountOwner) return false

        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: 'originChannel', customer: customer]).get()
        if (customerSignUpOriginChannel?.isApi()) return false

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) return false

        return true
    }

    private CampaignEventMessage validateSave(CampaignEventName eventName, Map data) {
        CampaignEventMessage validatedCampaignEventMessage = new CampaignEventMessage()

        if (!data) {
            DomainUtils.addError(validatedCampaignEventMessage, "O parâmetro data não pode estar nulo.")
            return validatedCampaignEventMessage
        }

        if (!eventName) {
            DomainUtils.addError(validatedCampaignEventMessage, "O parâmetro eventName não pode estar nulo.")
            return validatedCampaignEventMessage
        }

        return validatedCampaignEventMessage
    }
}
