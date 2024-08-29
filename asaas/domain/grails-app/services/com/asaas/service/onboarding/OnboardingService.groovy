package com.asaas.service.onboarding

import com.asaas.customer.BaseCustomer
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.customer.knowyourcustomerinfo.KnowYourCustomerInfoIncomeRange
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyDocumentationOnboardingAdapter
import com.asaas.user.UserUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class OnboardingService {

    def asyncActionService
    def accountActivationRequestService
    def createCampaignEventMessageService
    def customerDocumentProxyService
    def customerDocumentGroupProxyService
    def customerOnboardingService
    def customerUpdateRequestService
    def thirdPartyDocumentationOnboardingManagerService

    public String getCurrentStep(Customer customer) {
        if (UserUtils.isReauthenticatedAdminUser()) return

        if (!customer) return "createAccount"

        if (customer.hasUserWithSysAdminRole() && customer.customerRegisterStatus.generalApproval.isApproved()) return

        if (customerOnboardingService.hasFinished(customer.id)) return

        return getPendingStep(customer)
    }

    public CustomerUpdateRequest saveCommercialInfo(Customer customer, Map params) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = validateMobilePhone(customer, params.mobilePhone)
        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        validatedCustomerUpdateRequest = validateIfAddressContainsEmoji(params)
        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        CustomerUpdateRequest customerUpdateRequest = customerUpdateRequestService.saveNamedParams(customer, params)

        return customerUpdateRequest
    }

    public CustomerUpdateRequest savePersonTypeInfo(Customer customer, Map params) {
        CustomerUpdateRequest customerUpdateRequest = new CustomerUpdateRequest()

        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: "originChannel", customer: customer]).get()

        if (customerSignUpOriginChannel?.isERPProductPage() && CpfCnpjUtils.isCpf(params.cpfCnpj)) {
            DomainUtils.addError(customerUpdateRequest, "O campo deve ser preenchido com o CNPJ.")

            return customerUpdateRequest
        }

        customerUpdateRequest = customerUpdateRequestService.saveNamedParams(customer, params)

        return customerUpdateRequest
    }

    public CustomerUpdateRequest updateMobilePhone(Customer customer, String mobilePhone) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.query([provider: customer, sort: "id", order: "desc"]).get()

        if (!customerUpdateRequest) {
            AsaasLogger.error("Onboarding externo: Problemas ao alterar celular de ativação. Cliente não possui customerUpdateRequest. [customer: ${customer.id}]")
            return validatedCustomerUpdateRequest
        }

        if (customerUpdateRequest.mobilePhone == mobilePhone) return customerUpdateRequest

        if (customerUpdateRequest.status.isApproved()) {
            AsaasLogger.error("Onboarding externo: Não é possível alterar um customerUpdateRequest já aprovado. [customer: ${customer.id}]")
            return customerUpdateRequest
        }

        validatedCustomerUpdateRequest = validateMobilePhone(customer, mobilePhone)

        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        Map params = [mobilePhone: mobilePhone]

        return customerUpdateRequestService.saveNamedParams(customer, params)
    }

    public BaseCustomer saveBusinessActivityInfo(Customer customer, Map params) {
        BaseCustomer customerToBeUpdated = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)

        if (customerToBeUpdated) return processExistingCustomerUpdateRequest(customer, customerToBeUpdated, params)

        return processNewCustomerUpdateRequest(customer, params)
    }

    public Boolean isThirdPartyOnboardingCompleted(Customer customer) {
        ThirdPartyDocumentationOnboardingAdapter onboardingAdapter = thirdPartyDocumentationOnboardingManagerService.getLastExternalLink(customer.id)

        if (!onboardingAdapter) {
            AsaasLogger.info("OnboardingService.isThirdPartyOnboardingCompleted -> Verificando status de onboarding não encontrado. [customerId: ${customer.id}]")
            return false
        }

        return onboardingAdapter.status.isCompleted()
    }

    public Boolean hasAnyMandatoryStepPending(Customer customer) {
        if (customerOnboardingService.hasFinished(customer.id)) return false

        return customer.customerRegisterStatus.anyMandatoryIsPending()
    }

    public Boolean shouldRequestBusinessActivity(Customer customer) {
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)

        if (customerUpdateRequest?.businessActivity) return false

        if (CustomerBusinessActivity.query([exists: true, customer: customer]).get()) return false

        return true
    }

    public Boolean shouldSkipBankAccountStep(Customer customer) {
        if (customer.bankAccountInfoApprovalIsNotRequired()) return true

        if (BankAccountInfo.alreadyHaveMainAccount(customer)) return true

        if (BankAccountInfoUpdateRequest.query([exists: true, customer: customer]).get()) return true

        return false
    }

    public CustomerUpdateRequest sendCustomerUpdateRequestToAnalysisIfPossible(CustomerUpdateRequest customerUpdateRequest) {
        if (!customerUpdateRequest.businessActivity) return customerUpdateRequest

        if (!customerUpdateRequest.mobilePhone) return customerUpdateRequest

        CustomerUpdateRequest customerUpdateRequestResult = customerUpdateRequestService.processCustomerUpdateRequest(customerUpdateRequest)

        if (customerUpdateRequestResult.hasErrors()) {
            AsaasLogger.error("Onboarding externo: Erro ao enviar o CustomerUpdateRequest [${customerUpdateRequestResult.id}] para análise. Errors: [${customerUpdateRequestResult.errors.allErrors}]", new Exception())
        }

        saveErpCustomerIfNecessaryAndSendEventMessage(customerUpdateRequestResult.provider)

        return customerUpdateRequestResult
    }

    public Boolean shouldShowInternalThirdPartyOnboarding(Customer customer) {
        if (!AsaasEnvironment.isProduction()) {
            if (!customer.accountOwner) return false
            if (!CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.ENABLE_HOMOLOG_THIRD_PARTY_ONBOARDING_FOR_CHILD_ACCOUNTS)) return false
        }

        if (customer.accountDisabled()) return false

        if (!customerDocumentGroupProxyService.hasCustomerDocumentGroupSaved(customer)) return true

        if (customerDocumentProxyService.hasCustomerIdentificationDocumentNotSentOrRejected(customer)) return true

        return false
    }

    private String getPendingStep(Customer customer) {
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)

        if (!customerUpdateRequest?.cpfCnpj && !customer.cpfCnpj) return "personTypeInfo"

        if (!hasCommercialInfoCompleted(customerUpdateRequest, customer)) return "commercialInfo"

        if (shouldRequestBusinessActivity(customer)) return "businessActivity"

        return
    }

    private Boolean hasCommercialInfoCompleted(CustomerUpdateRequest customerUpdateRequest, Customer customer) {
        def domain = customerUpdateRequest ?: customer

        if (!domain.mobilePhone || !hasCompleteAddress(domain)) return false
        if (domain.isNaturalPerson() && !domain.name) return false
        if (domain.isLegalPerson() && (!domain.company || !domain.companyType)) return false
        return true
    }

    private Boolean hasCompleteAddress(domain) {
        return domain.address && domain.province && domain.addressNumber && domain.postalCode && (domain.city || domain.cityString)
    }

    private CustomerUpdateRequest validateMobilePhone(Customer customer, String mobilePhone) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()

        if (!mobilePhone) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar um número de telefone celular.")
            return validatedCustomerUpdateRequest
        }

        AccountActivationRequest validatedAccountActivationRequest = accountActivationRequestService.validateMobilePhone(customer, mobilePhone)

        if (validatedAccountActivationRequest.hasErrors()) {
            DomainUtils.addError(validatedCustomerUpdateRequest, Utils.getMessageProperty(validatedAccountActivationRequest.errors.allErrors[0]))
        }

        return validatedCustomerUpdateRequest
    }

    private CustomerUpdateRequest validateIfAddressContainsEmoji(Map params) {
        CustomerUpdateRequest addressInfoInputReference = new CustomerUpdateRequest()

        if (StringUtils.textHasEmoji(params.address) || StringUtils.textHasEmoji(params.province) || StringUtils.textHasEmoji(params.complement)) {
            DomainUtils.addError(addressInfoInputReference, "Não é permitido o uso de emoji nos dados de endereço.")
        }

        return addressInfoInputReference
    }

    private BaseCustomer processExistingCustomerUpdateRequest(Customer customer, BaseCustomer customerToBeUpdated, Map params) {
        customerToBeUpdated = customerUpdateRequestService.saveNamedParams(customer, params)
        if (customerToBeUpdated.hasErrors()) return handleCustomerUpdateRequestErrors(customer, customerToBeUpdated)

        return sendCustomerUpdateRequestToAnalysisIfPossible(customerToBeUpdated)
    }

    private BaseCustomer processNewCustomerUpdateRequest(Customer customer, Map params) {
        BaseCustomer customerToBeUpdated = createNewCustomerUpdateRequest(customer, params)
        if (customerToBeUpdated.hasErrors()) return handleCustomerUpdateRequestErrors(customer, customerToBeUpdated)

        saveErpCustomerIfNecessaryAndSendEventMessage(customer)
        return customerToBeUpdated
    }

    private BaseCustomer createNewCustomerUpdateRequest(Customer customer, Map params) {
        Map customerUpdateRequestParams = createCustomerUpdateRequestParamsBasedOnCustomer(customer, params.businessActivity, params.businessActivityDescription)
        BaseCustomer customerUpdateRequest = customerUpdateRequestService.save(customer.id, customerUpdateRequestParams)
        return customerUpdateRequest
    }

    private BaseCustomer handleCustomerUpdateRequestErrors(Customer customer, BaseCustomer customerToBeUpdated) {
        AsaasLogger.error("OnboardingService.handleCustomerUpdateRequestErrors >> Erro ao salvar businessActivity para o customer: [${customer.id}]. Erros: [${DomainUtils.getValidationMessagesAsString(customerToBeUpdated.getErrors())}]")

        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()
        DomainUtils.addError(validatedCustomerUpdateRequest, "Ocorreu um erro ao salvar os dados do negócio. Por favor, recarregue a página e tente novamente.")

        return validatedCustomerUpdateRequest
    }

    private void saveErpCustomerIfNecessaryAndSendEventMessage(Customer customer) {
        CustomerSignUpOriginChannel customerSignUpOriginChannel = CustomerSignUpOrigin.query([column: "originChannel", customer: customer]).get()
        if (customerSignUpOriginChannel?.isERPProductPage()) asyncActionService.saveSendCustomerToAsaasErp(customer.id)

        createCampaignEventMessageService.saveForOnboardingFinished(customer)
    }

    private Map createCustomerUpdateRequestParamsBasedOnCustomer(Customer customer, Long businessActivity, String businessActivityDescription ) {
        Map params = [:]

        params.email = customer.email

        if (customer.name) params.name = customer.name
        if (customer.cpfCnpj) params.cpfCnpj = customer.cpfCnpj
        if (customer.personType) params.personType = customer.personType
        if (customer.birthDate) params.birthDate = customer.birthDate
        if (customer.company) params.company = customer.company
        if (customer.companyType) params.companyType = customer.companyType

        if (customer.phone) params.phone = customer.phone
        if (customer.mobilePhone) params.mobilePhone = customer.mobilePhone
        if (customer.address) params.address = customer.address
        if (customer.addressNumber) params.addressNumber = customer.addressNumber
        if (customer.complement) params.complement = customer.complement
        if (customer.postalCode) params.postalCode = customer.postalCode.toString()
        if (customer.province) params.province = customer.province
        if (customer.city) params.city = customer.city
        if (customer.cityString) params.cityString = customer.cityString
        if (customer.state) params.state = customer.state
        if (customer.additionalEmails) params.additionalEmails = customer.additionalEmails
        if (customer.site) params.site = customer.site
        if (customer.responsibleName) params.responsibleName = customer.responsibleName
        if (customer.inscricaoEstadual) params.inscricaoEstadual = customer.inscricaoEstadual

        KnowYourCustomerInfoIncomeRange incomeRange = KnowYourCustomerInfo.findIncomeRange(customer)
        if (incomeRange) params.incomeRange = incomeRange.toString()

        params.bypassIncomeRangeMandatoryValidation = true
        params.businessActivity = businessActivity
        params.businessActivityDescription = businessActivityDescription

        return params
    }
}
