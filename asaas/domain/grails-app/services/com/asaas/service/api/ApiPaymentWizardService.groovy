package com.asaas.service.api

import com.asaas.api.ApiPaymentParser
import com.asaas.api.paymentWizard.ApiPaymentWizardParser
import com.asaas.api.paymentWizard.ApiPaymentWizardSaveParser
import com.asaas.api.paymentWizard.ApiPaymentWizardSummaryBuilder
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.payment.Payment
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.segment.PaymentOriginTracker

import grails.transaction.Transactional

@Transactional
class ApiPaymentWizardService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentWizardService
    def pixAddressKeyService

    public Map index(Map params) {
        Customer customer = getProviderInstance(params)
        CustomerAutomaticReceivableAnticipationConfig automaticReceivableAnticipation = CustomerAutomaticReceivableAnticipationConfig.query([customerId: customer.id]).get()

        Map responseItem = [:]
        responseItem.canOfferAutomaticPixAddressKeyCreation = pixAddressKeyService.offerAutomaticPixAddressKeyCreationOnWizard(customer)
        responseItem.canCreateAutomaticPixAddressKey = pixAddressKeyService.canCreateAutomaticCustomerPixAddressKey(customer)
        responseItem.interestConfig = ApiPaymentWizardParser.buildInterestConfig(customer)
        responseItem.hasAutomaticAnticipationActivated = automaticReceivableAnticipation?.active
        responseItem.canRequestFeedback = false

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)
        Boolean hasCreatedPayments = customer.hasCreatedPayments()

        Map parsedFields = ApiPaymentWizardSaveParser.parseRequestParams(params)

        Payment payment = paymentWizardService.save(parsedFields, null, customer)

        if (payment.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(payment)
        }

        Map responseItem = ApiPaymentParser.buildResponseItem(payment, [namespace: params.namespace, expandCustomer: true])
        responseItem.isFirstPayment = !hasCreatedPayments
        responseItem.shouldAskCustomerAcquisitionChannel = !hasCreatedPayments

        trackPaymentCreated([providerId: customer.id, payment: payment])

        if (parsedFields.detachedPaymentData) {
            PaymentOriginTracker.trackApiCreation(payment, null, null, "PAYMENT_WIZARD")
        } else if (parsedFields.installmentsData) {
            PaymentOriginTracker.trackApiCreation(null, payment.installment, null, "PAYMENT_WIZARD")
        } else {
            PaymentOriginTracker.trackApiCreation(null, null, payment.getSubscription(), "PAYMENT_WIZARD")
        }

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public Map getSummary(Map params) {
        return apiResponseBuilderService.buildSuccess(ApiPaymentWizardSummaryBuilder.build(getProviderInstance(params), params))
    }

    public Map validateAnticipationRequest(Map params) {
        Customer customer = getProviderInstance(params)

        ReceivableAnticipationValidator receivableAnticipationValidator = new ReceivableAnticipationValidator(true)

        List<String> denialReasonsList = []

        Map validationRequestData = ApiPaymentWizardParser.parseAnticipationSimulationValidationRequest(params)
        List<ReceivableAnticipationNonAnticipableReasonVO> paymentDenialReasonsList = receivableAnticipationValidator.validateSimulation(customer, validationRequestData)
        if (paymentDenialReasonsList) {
            denialReasonsList.addAll(paymentDenialReasonsList.collect { it.message })
        }

        if (params.customer != null) {
            CustomerAccount customerAccount
            if (params.customer.id) customerAccount = CustomerAccount.find(params.customer.id, customer.id)

            List<ReceivableAnticipationNonAnticipableReasonVO> customerAccountDenialReasonsList = receivableAnticipationValidator.preValidateCustomerAccount(customer, validationRequestData.billingType, customerAccount, params.customer.mobilePhone, params.customer.phone, params.customer.name, params.customer.cpfCnpj)
            if (customerAccountDenialReasonsList != null) {
                 denialReasonsList.addAll(customerAccountDenialReasonsList.collect { it.message })
            }
        }

        return [denialReasons: denialReasonsList]
    }
}
