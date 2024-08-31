package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiExternalOnboardingParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiProviderParser
import com.asaas.api.ApiRevenueServiceRegisterParser
import com.asaas.api.externalOnboarding.ApiExternalOnboardingStepBuilder
import com.asaas.api.externalOnboarding.MobileExternalOnboardingStep
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.customer.CustomerValidator
import com.asaas.customer.SignUpChannel
import com.asaas.domain.businessactivity.BusinessActivity
import com.asaas.domain.city.City
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ApiExternalOnboardingService extends ApiBaseService {

    def apiBankAccountInfoService
    def apiResponseBuilderService
    def customerOnboardingService
    def customerProofOfLifeService
    def onboardingService
    def revenueServiceRegisterService

    public Map getCustomerInfo(Map params) {
        Map responseMap = [:]

        Customer customer = getProviderInstance(params)
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)

        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: "originChannel", customer: customer]).get()

        responseMap.commercialInfo = ApiExternalOnboardingParser.buildCustomerResponseItem(customerUpdateRequest ?: customer)
        responseMap.isErpOriginChannel = customerSignUpOriginChannel?.isERPProductPage()
        responseMap.shouldRequestAccountValidationCode = !customer.status.isActive()

        responseMap.bankAccountInfo = apiBankAccountInfoService.find(params)
        responseMap.shouldRequestBusinessActivity = ApiMobileUtils.getApplicationType().isAsaas() && onboardingService.shouldRequestBusinessActivity(customer)
        responseMap.isMobilePhoneValidationMandatory = ApiMobileUtils.getApplicationType().isMoney()

        RevenueServiceRegister revenueServiceRegister
        if (customerUpdateRequest) {
            revenueServiceRegister = RevenueServiceRegister.findLatest(customerUpdateRequest.cpfCnpj)
        }

        if (revenueServiceRegister) {
            responseMap.revenueServiceRegister = ApiRevenueServiceRegisterParser.buildResponseItem(revenueServiceRegister)
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map validateIfCpfCnpjCanBeUsed(Map params) {
        Map responseMap = [:]
        CustomerValidator customerValidator = new CustomerValidator()
        BusinessValidation validatedCpfCnpj = customerValidator.validateCpfCnpj(params.cpfCnpj, false, getProviderInstance(params))

        responseMap.canCpfCnpjBeUsed = validatedCpfCnpj.isValid()
        if (!responseMap.canCpfCnpjBeUsed) {
            SignUpChannel signedUpThrough = Customer.query([cpfCnpj: params.cpfCnpj, column: "signedUpThrough"]).get()
            responseMap.signedUpThrough = signedUpThrough?.toString()
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map validateBirthDate(Map params) {
        Boolean isValidBirthDate = Customer.olderThanMinimumRequired(ApiBaseParser.parseDate(params.birthDate))

        return apiResponseBuilderService.buildSuccess([isValidBirthDate: isValidBirthDate])
    }

    public Map isCompleted(params) {
        Map responseMap = [isCompleted: true]

        Customer customer = getProviderInstance(params)
        Boolean isGeneralApprovedOrRejected = customer.customerRegisterStatus.generalApproval.isApproved() || customer.customerRegisterStatus.generalApproval.isRejected()

        if (customerOnboardingService.hasFinished(customer.id) || isGeneralApprovedOrRejected) {
            return responseMap
        }

        if (shouldUpdateCustomerProofOfLifeToSelfie(customer, params)) {
            customerProofOfLifeService.updateToSelfieIfPossible(customer)
        }

        Map customerInfo = getCustomerInfo(params)

        if (!customerInfo.commercialInfo.mobilePhone) {
            responseMap.isCompleted = false
        }

        if (customerInfo.shouldRequestBusinessActivity) {
            responseMap.isCompleted = false
        }

        if (customerInfo.shouldRequestAccountValidationCode && customerInfo.isMobilePhoneValidationMandatory) {
            responseMap.isCompleted = false
        }

        Boolean customerHasPassedLastOnboardingStep = customerInfo.commercialInfo.mobilePhone.asBoolean()
        if (customerHasPassedLastOnboardingStep && ApiMobileUtils.getApplicationType().isMoney() && onboardingService.shouldRequestBusinessActivity(customer)) {
            fillAsaasMoneyCustomerBusinessActivity(customer)
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map saveCommercialInfo(Map params) {
        Map fields = ApiExternalOnboardingParser.parseCommercialInfoParams(params)

        Customer customer = getProviderInstance(params)

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)
        if (!customerUpdateRequest) customerUpdateRequest = new CustomerUpdateRequest()

        if (shouldFindRevenueServiceRegister(customerUpdateRequest, fields)) {
            RevenueServiceRegister revenueServiceRegister = findRevenueServiceRegister(customerUpdateRequest, fields)
            if (revenueServiceRegister) {
                if (customerUpdateRequest.personType.isFisica()) {
                    fields.name = revenueServiceRegister.name
                } else {
                    if (revenueServiceRegister.getCompanyNames().size() == 1) {
                        fields.company = revenueServiceRegister.getCompanyNames().first()
                        fields.name = fields.company
                    }

                    fields.companyType = revenueServiceRegister.getCompanyType()
                }
            }
        }

        customerUpdateRequest.provider = customer
        customerUpdateRequest.email = customer.email
        customerUpdateRequest.personType = fields.personType
        customerUpdateRequest.cpfCnpj = fields.cpfCnpj
        customerUpdateRequest.birthDate = fields.birthDate

        customerUpdateRequest.name = fields.name
        customerUpdateRequest.company = fields.company
        customerUpdateRequest.companyType = fields.companyType
        customerUpdateRequest.mobilePhone = fields.mobilePhone
        customerUpdateRequest.postalCode = fields.postalCode
        customerUpdateRequest.address = fields.address
        customerUpdateRequest.addressNumber = fields.addressNumber
        customerUpdateRequest.complement = fields.complement
        customerUpdateRequest.province = fields.province

        if (fields.containsKey("city")) {
            customerUpdateRequest.city = City.get(fields.city)
        }

        if (ApiMobileUtils.getApplicationType().isMoney() && fields.containsKey("postalCode")) {
            fields.businessActivity = BusinessActivity.OTHERS_SERVICE_ID
            fields.businessActivityDescription = "Asaas Money"
        }

        if (fields.containsKey("businessActivity")) {
            customerUpdateRequest.businessActivity = BusinessActivity.get(fields.businessActivity)
            customerUpdateRequest.businessActivityDescription = fields.businessActivityDescription
        }
        customerUpdateRequest.save(failOnError: true)

        onboardingService.sendCustomerUpdateRequestToAnalysisIfPossible(customerUpdateRequest)

        if (customerUpdateRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(customerUpdateRequest)
        }

        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(customerUpdateRequest))
    }

    public Map shouldRequestCustomerDocuments(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess([shouldRequestCustomerDocuments: false])
    }

    public Map getPendingCustomerDocuments(Map params) {
        Customer customer = getProviderInstance(params)

        return apiResponseBuilderService.buildSuccess([pendingDocuments: []])
    }

    public Map nextStep(Map params) {
        Customer customer = getProviderInstance(params)
        Map customerInfo = getCustomerInfo(params)

        MobileExternalOnboardingStep currentStep = MobileExternalOnboardingStep.convert(params.currentStep)
        MobileExternalOnboardingStep nextStep = ApiExternalOnboardingStepBuilder.findNextStep(customer, customerInfo, currentStep)

        return apiResponseBuilderService.buildSuccess([step: nextStep.toString()])
    }

    public Map previousStep(Map params) {
        Map customerInfo = getCustomerInfo(params)

        MobileExternalOnboardingStep currentStep = MobileExternalOnboardingStep.convert(params.currentStep)
        MobileExternalOnboardingStep previousStep = ApiExternalOnboardingStepBuilder.findPreviousStep(customerInfo, currentStep)

        return apiResponseBuilderService.buildSuccess([step: previousStep?.toString()])
    }

    private Boolean shouldUpdateCustomerProofOfLifeToSelfie(Customer customer, Map params) {
        Boolean hasSentBankAccount = apiBankAccountInfoService.find(params).bank.asBoolean()
        if (hasSentBankAccount) return false

        return true
    }

    private RevenueServiceRegister findRevenueServiceRegister(CustomerUpdateRequest customerUpdateRequest, Map fields) {
        RevenueServiceRegister revenueServiceRegister

        Utils.withNewTransactionAndRollbackOnError({
            if (fields.personType.isFisica()) {
                revenueServiceRegister = revenueServiceRegisterService.findNaturalPerson(fields.cpfCnpj, fields.birthDate)
            } else {
                revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(fields.cpfCnpj)
            }
        }, [logErrorMessage: "ApiExternalOnboardingService.findRevenueServiceRegister ->>> Não foi possível buscar o RevenueServiceRegister do customer [${customerUpdateRequest.provider.id}]"])

        return revenueServiceRegister
    }

    private Boolean shouldFindRevenueServiceRegister(CustomerUpdateRequest customerUpdateRequest, Map fields) {
        if (!customerUpdateRequest.personType) return false

        if (customerUpdateRequest.personType.isFisica() && fields.cpfCnpj && fields.birthDate) {
            return fields.cpfCnpj != customerUpdateRequest.cpfCnpj || customerUpdateRequest.birthDate != fields.birthDate
        }

        if (customerUpdateRequest.personType.isJuridica() && fields.cpfCnpj) {
            return customerUpdateRequest.cpfCnpj != fields.cpfCnpj
        }

        return false
    }

    private void fillAsaasMoneyCustomerBusinessActivity(Customer customer) {
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)
        if (!customerUpdateRequest) {
            AsaasLogger.warn("ApiExternalOnboardingService.fillAsaasMoneyCustomerBusinessActivity >>> Não foi possível encontrar o CustomerUpdateRequest do customer [${customer.id}]")
            return
        }

        customerUpdateRequest.businessActivity = BusinessActivity.get(BusinessActivity.OTHERS_SERVICE_ID)
        customerUpdateRequest.businessActivityDescription = "Asaas Money"
        customerUpdateRequest.save(failOnError: true)

        onboardingService.sendCustomerUpdateRequestToAnalysisIfPossible(customerUpdateRequest)
    }
}
