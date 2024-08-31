package com.asaas.service.customer.customerupdaterequest

import com.asaas.customer.BaseCustomer
import com.asaas.customer.CompanyType
import com.asaas.customer.CustomerValidator
import com.asaas.customer.PersonType
import com.asaas.customer.knowyourcustomerinfo.KnowYourCustomerInfoIncomeRange
import com.asaas.customercommercialinfo.adapter.PeriodicCustomerCommercialInfoUpdateAdapter
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

import static grails.async.Promises.task

@Transactional
class CustomerUpdateRequestService {

    def bacenCcsService
    def bankAccountInfoService
    def boletoChangeInfoRequestService
    def customerAccountService
    def customerBusinessActivityService
    def customerCheckoutLimitService
    def customerCommercialInfoExpirationService
    def customerCriticalActionService
    def customerDocumentProxyService
    def customerFeatureService
    def customerGeneralAnalysisService
    def customerInteractionService
    def customerRegisterStatusService
    def customerService
    def crypterService
    def dataEnhancementManagerService
    def economicActivityService
    def knowYourCustomerInfoService
    def leadDataService
    def nexinvoiceCustomerUpdateAsyncActionService
    def paymentService
    def receivableAnticipationPartnerConfigService
    def revenueServiceRegisterService
    def sdnEntryService
    def securityEventNotificationService
    def springSecurityService

	public save(Long customerId, Map params) {
        Customer customer = Customer.read(customerId)

        customer.validateAccountDisabled()

        BaseCustomer customerToBeUpdated = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer) ?: customer

        Map parsedParams = parseSaveParams(customerToBeUpdated, params)

        CustomerUpdateRequest validatedCustomerUpdateRequest = validateSaveParams(customer, parsedParams)
        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        if (!hasCustomerUpdateRequestChanges(customer, customerToBeUpdated, parsedParams)) {
            customerCommercialInfoExpirationService.save(customer)

            return customerToBeUpdated
        }

        Customer validationCustomer = customerService.validateCommercialInfoUpdate(customerId, parsedParams)

        if (validationCustomer.hasErrors()) return validationCustomer

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.query([provider: customer, status: Status.PENDING]).get() ?: new CustomerUpdateRequest(provider: customer, status: Status.PENDING)

        customerUpdateRequest.properties = parsedParams
        customerUpdateRequest.incomeValue = null
        customerUpdateRequest.save(flush: true, failOnError: false)

        if (parsedParams.incomeValue) {
            customerUpdateRequest.incomeValue = crypterService.encryptDomainProperty(customerUpdateRequest, "incomeValue", parsedParams.incomeValue.toString())
            customerUpdateRequest.save(failOnError: false)
        }

        notifyAboutCustomerUpdateRequestedIfNecessary(params)
        customerService.updateBasedOnPersonType(customerUpdateRequest)
        nexinvoiceCustomerUpdateAsyncActionService.saveIfNecessary(customer.id)

        CustomerUpdateRequest processedCustomerUpdateRequest = processCustomerUpdateRequest(customerUpdateRequest)
        if (processedCustomerUpdateRequest.hasErrors()) return processedCustomerUpdateRequest

        return processedCustomerUpdateRequest
    }

    public CustomerUpdateRequest processCustomerUpdateRequest(CustomerUpdateRequest customerUpdateRequest) {
        if (customerUpdateRequest.hasErrors()) return customerUpdateRequest

        Customer customer = customerUpdateRequest.provider

        cancelRequestsAwaitingActionAuthorization(customer)

        Boolean commercialInfoUpdate = CustomerCriticalActionConfig.query([column: "commercialInfoUpdate", customerId: customer.id]).get()
        if (commercialInfoUpdate && hasApprovedUpdateRequest(customer) && Payment.received([customer: customer]).count() > 0) {
            customerUpdateRequest.status = Status.AWAITING_ACTION_AUTHORIZATION
            CriticalAction.saveCommercialInfoUpdate(customerUpdateRequest)
            updateCustomerRegisterStatus(customerUpdateRequest)

            return customerUpdateRequest
        }

        return onCriticalActionAuthorization(customerUpdateRequest)
    }

    public CustomerUpdateRequest saveNamedParams(Customer customer, Map params) {
        customer.validateAccountDisabled()

        CustomerUpdateRequest validatedCustomerUpdateRequest = validateNamedParams(customer, params)
        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.query([provider: customer, status: Status.PENDING]).get() ?: new CustomerUpdateRequest(provider: customer, status: Status.PENDING)

        List<String> savableProperties = [
            "personType", "cpfCnpj", "birthDate", "email", "name", "company", "companyType", "mobilePhone",
            "postalCode", "address", "addressNumber", "complement", "cityString", "province", "city", "uf",
            "businessActivity", "businessActivityDescription"]

        customerUpdateRequest.properties[savableProperties] = params
        customerUpdateRequest.save(failOnError: true)

        customerService.updateBasedOnPersonType(customerUpdateRequest)

        return customerUpdateRequest
    }

	private void updateCustomerRegisterStatus(CustomerUpdateRequest customerUpdateRequest) {
		Status newStatus

		CustomerUpdateRequest previousUpdateRequest = CustomerUpdateRequest.query([provider: customerUpdateRequest.provider, notId: customerUpdateRequest.id, statusList: [Status.APPROVED, Status.DENIED]]).get()

		if (previousUpdateRequest?.status == Status.APPROVED) {
			newStatus = Status.APPROVED
		} else if (previousUpdateRequest?.status == Status.PENDING) {
			newStatus = Status.AWAITING_APPROVAL
		} else if (previousUpdateRequest?.status == Status.DENIED) {
			newStatus = Status.REJECTED
		} else {
			newStatus = Status.PENDING
		}

		customerRegisterStatusService.updateCommercialInfoStatus(customerUpdateRequest.provider, newStatus)
	}

	public void cancelRequestsAwaitingActionAuthorization(Customer provider) {
    	for (CustomerUpdateRequest request in CustomerUpdateRequest.query([provider: provider, status: Status.AWAITING_ACTION_AUTHORIZATION]).list()) {
    		request.status = Status.CANCELLED
    		request.save(flush: true, failOnError: true)

            CriticalAction.deleteNotAuthorized(request)
    	}
    }

    public void onCriticalActionCancellation(CriticalAction action) {
    	action.customerUpdateRequest.status = Status.CANCELLED
        action.customerUpdateRequest.save(failOnError: true)
    }

	public CustomerUpdateRequest onCriticalActionAuthorization(CustomerUpdateRequest customerUpdateRequest) {
        Customer customer = customerUpdateRequest.provider

        if (updateRequiresApproval(customerUpdateRequest)) {
            customerUpdateRequest.status = Status.PENDING
            customerRegisterStatusService.updateCommercialInfoStatus(customer, Status.AWAITING_APPROVAL)

            if (customer.cpfCnpj && !CpfCnpjUtils.isSameDocument(customer.cpfCnpj, customerUpdateRequest.cpfCnpj)) {
                customerDocumentProxyService.updateDocumentsWhenCpfCnpjChanged(customer, customerUpdateRequest)
                bankAccountInfoService.changeToThirdyPartyAccountAndSendToApproval(customer)
            }
        }

        if (customer.city?.id != customerUpdateRequest.city?.id) {
            customerFeatureService.enableInvoiceFeatureIfNecessary(customer, customerUpdateRequest.city)
        }

        if (!hasApprovedUpdateRequest(customer)) {
            Customer updatedCustomer = customerService.updateCommercialInfo(customerUpdateRequest.provider.id, buildCustomerPropertiesMap(customerUpdateRequest))
            if (updatedCustomer.hasErrors()) {
                throw new ValidationException("CustomerUpdateRequestService.onCriticalActionAuthorization >> Erro ao atualizar informações comerciais [${customerUpdateRequest.id}]", updatedCustomer.errors)
            }
        }

        sdnEntryService.reportAboutCustomerInSDNListIfNecessary(customer)

        if (customer.hasAccountAutoApprovalEnabled()) {
            approveAutomatically(customerUpdateRequest)
        } else {
            onRevenueServiceRegisterFound(customerUpdateRequest, customer.getRevenueServiceRegister())
        }

        customerCommercialInfoExpirationService.save(customer)

        return customerUpdateRequest
	}

    private Boolean updateRequiresApproval(CustomerUpdateRequest customerUpdateRequest) {
        if (!CpfCnpjUtils.isSameDocument(customerUpdateRequest.provider.cpfCnpj, customerUpdateRequest.cpfCnpj)) return true

        if (!customerUpdateRequest.businessActivity) return true

        RevenueServiceRegister revenueServiceRegister = customerUpdateRequest.provider.getRevenueServiceRegister()
        if (!revenueServiceRegister || revenueServiceRegister.hasErrors()) return true

        if (!revenueServiceRegister.hasAcceptableStatus()) return true

        return false
    }

    public void analyzeCustomerUpdateRequestAutomatically() {
        List<Long> customerUpdateRequestIdList = CustomerUpdateRequest.queueToAutomaticAnalysis([column: "id"]).list([max: CustomerUpdateRequest.AUTOMATIC_ANALYSIS_LIMIT])

        for (Long customerUpdateRequestId in customerUpdateRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError ({
                AsaasLogger.info("Analisando a solicitação de atualização de dados comerciais [${customerUpdateRequestId}] automaticamente.")

                CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.get(customerUpdateRequestId)

                RevenueServiceRegister revenueServiceRegister

                if (CpfCnpjUtils.isCnpj(customerUpdateRequest.cpfCnpj)) {
                    revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(customerUpdateRequest.cpfCnpj)
                } else {
                    revenueServiceRegister = revenueServiceRegisterService.findNaturalPerson(customerUpdateRequest.cpfCnpj, customerUpdateRequest.birthDate)
                }

                onRevenueServiceRegisterFound(customerUpdateRequest, revenueServiceRegister)

                if (!revenueServiceRegister || revenueServiceRegister.hasErrors()) {
                    AsaasLogger.info("Registro na receita não encontrado para a atualização de dados comerciais [${customerUpdateRequestId}].")
                    saveNextAttempt(customerUpdateRequestId)
                }
            }, [onError: { Exception e -> saveNextAttempt(customerUpdateRequestId) }])
        }
    }

    private void saveNextAttempt(Long customerUpdateRequestId) {
        Utils.withNewTransactionAndRollbackOnError ({
            CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.get(customerUpdateRequestId)

            customerUpdateRequest.automaticAnalysisAttempts++

            customerUpdateRequest.automaticAnalysisNextAttempt = CustomDateUtils.sumMinutes(new Date(), CustomerUpdateRequest.MINUTES_TO_NEXT_AUTOMATIC_ANALYSIS_ATTEMPT)

            customerUpdateRequest.save(flush: true, failOnError: true)
        })
    }

    private void onRevenueServiceRegisterFound(CustomerUpdateRequest customerUpdateRequest, RevenueServiceRegister revenueServiceRegister) {
        if (!revenueServiceRegister || revenueServiceRegister.hasErrors()) return

        customerUpdateRequest = updateBasedOnRevenueServiceRegister(customerUpdateRequest, revenueServiceRegister)

        startDataEnhancement(customerUpdateRequest.cpfCnpj, customerUpdateRequest.birthDate)

        if (!updateRequiresApproval(customerUpdateRequest)) approveAutomatically(customerUpdateRequest)
    }

    private CustomerUpdateRequest updateBasedOnRevenueServiceRegister(CustomerUpdateRequest customerUpdateRequest, RevenueServiceRegister revenueServiceRegister) {
        if (revenueServiceRegister.isNaturalPerson()) {
            customerUpdateRequest.name = revenueServiceRegister.name
            customerUpdateRequest.birthDate = revenueServiceRegister.birthDate
        } else if (revenueServiceRegister.isLegalPerson()) {
            CompanyType revenueCompanyType = revenueServiceRegister.getCompanyType()
            if (revenueCompanyType) customerUpdateRequest.companyType = revenueCompanyType

            if (!(customerUpdateRequest.name?.toLowerCase() in revenueServiceRegister.getCompanyNames()*.toLowerCase())) {
                String companyName = revenueServiceRegister.tradingName ?: revenueServiceRegister.corporateName
                customerUpdateRequest.name = companyName
                customerUpdateRequest.company = companyName
            }
        }

        customerUpdateRequest.save(flush: true, failOnError: true)

        economicActivityService.associateAllCustomer(customerUpdateRequest.provider, revenueServiceRegister.buildEconomicActivitiesMapList())
        return customerUpdateRequest
    }

    private void startDataEnhancement(String cpfCnpj, Date birthDate) {
        task {
            Utils.withNewTransactionAndRollbackOnError({
                dataEnhancementManagerService.save(cpfCnpj, birthDate)
            })
        }
    }

	private Boolean hasApprovedUpdateRequest(Customer provider) {
		return CustomerUpdateRequest.query(provider: provider, status: Status.APPROVED).count() > 0
	}

	public CustomerUpdateRequest findLatestFromCustomer(Customer provider) {
		List<CustomerUpdateRequest> requests = CustomerUpdateRequest.executeQuery("from CustomerUpdateRequest where provider.id = :providerId and deleted = false order by id desc", [providerId: provider.id])

		if (requests.size() == 0)
			return null
		else
			return requests.get(0)
	}

	public void approve(Long customerUpdateRequestId, Map params) {
		CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.get(customerUpdateRequestId)
        Customer customer = customerUpdateRequest.provider

        if (customer.email != customerUpdateRequest.email) {
            leadDataService.saveArchiveOldAndCreateNewLeadIfPossible(customer.id, customerUpdateRequest.email)
        }

		Map customerProperties = buildCustomerPropertiesMap(customerUpdateRequest)

		bankAccountInfoService.changeForThirdPartyAccountIfNecessary(customerUpdateRequest)

        receivableAnticipationPartnerConfigService.setPartnerConfigStatusAsPendingIfNecessary(customerUpdateRequest)

        if (!CpfCnpjUtils.isSameDocument(customer.cpfCnpj, customerUpdateRequest.cpfCnpj)) {
            bacenCcsService.updateForCustomerCpfCnpjChange(customer.cpfCnpj, customerUpdateRequest.cpfCnpj, customer)
        }

		Customer updatedCustomer = customerService.updateCommercialInfo(customer.id, customerProperties)

        if (updatedCustomer.hasErrors()) {
            throw new ValidationException("CustomerUpdateRequestService.approve >> Erro ao aprovar atualização de dados comerciais [${customerUpdateRequestId}]", updatedCustomer.errors)
        }

		customerUpdateRequest.status = Status.APPROVED
		customerUpdateRequest.denialReason = ''
		customerUpdateRequest.user = springSecurityService.currentUser
		customerUpdateRequest.observations = params.observations
		customerUpdateRequest.save(flush: true, failOnError: true)

		customerRegisterStatusService.updateCommercialInfoStatus(customer, Status.APPROVED)

		customerInteractionService.saveCustomerUpdateRequestApproval(customer.id, customerUpdateRequest.observations)

		boletoChangeInfoRequestService.save(customer.id, [transferor: customer.getProviderName(), cpfCnpj: customer.cpfCnpj, receiveAfterDueDate: true])

		paymentService.registerBankSlips(customer.listPaymentsToBeRegistered())

		customerAccountService.updateAsaasCustomerAccountFromProviderIfExists(customer)

		economicActivityService.trackEconomicActivity(customerUpdateRequest.providerId)

        if (customerUpdateRequest.businessActivity) {
            customerBusinessActivityService.save(customer, customerUpdateRequest.businessActivity.id, customerUpdateRequest.businessActivityDescription)
        }

        setConfigAccordinglyCompanyType(customer)

        if (customer.hadGeneralApproval() || customer.hasAccountAutoApprovalEnabled()) {
            customerGeneralAnalysisService.approveAutomaticallyIfPossible(customer)
        }
    }

	public CustomerUpdateRequest findPreviousCustomerUpdateRequest(CustomerUpdateRequest customerUpdateRequest) {
		return findLastApprovedForCustomer(customerUpdateRequest) ?: findLastForCustomer(customerUpdateRequest)
	}

	private CustomerUpdateRequest findLastApprovedForCustomer(CustomerUpdateRequest customerUpdateRequest) {
		Map namedParams = [customerUpdateRequestId: customerUpdateRequest.id, provider: customerUpdateRequest.provider, approved: Status.APPROVED]

		return CustomerUpdateRequest.executeQuery("""from CustomerUpdateRequest
                                                    where id = (select max(id)
                                                                  from CustomerUpdateRequest
                                                                 where id < :customerUpdateRequestId
                                                                   and provider = :provider
                                                                   and status = :approved)""", namedParams)[0]
	}

	private CustomerUpdateRequest findLastForCustomer(CustomerUpdateRequest customerUpdateRequest) {
		return CustomerUpdateRequest.executeQuery("""from CustomerUpdateRequest
                                                    where id = (select max(id) from CustomerUpdateRequest where id < :customerUpdateRequestId and provider = :provider)""",
		                                          [customerUpdateRequestId: customerUpdateRequest.id, provider: customerUpdateRequest.provider])[0]
	}

	private Boolean addressHasChanged(CustomerUpdateRequest customerUpdateRequest) {
		Customer customer = customerUpdateRequest.provider

        if (customer.address != customerUpdateRequest.address) return true
        if (customer.addressNumber != customerUpdateRequest.addressNumber) return true
        if (customer.province != customerUpdateRequest.province) return true
        if (customer.city != customerUpdateRequest.city) return true
        if (customer.postalCode != customerUpdateRequest.postalCode) return true

        return false
	}

	private void approveAutomatically(CustomerUpdateRequest customerUpdateRequest) {
        String observations = Utils.getMessageProperty("system.automaticApproval.description")
		approve(customerUpdateRequest.id, [observations: observations])
	}

    private void setConfigAccordinglyCompanyType(Customer customer) {
        if (!customer.companyType) return

        if (customer.companyType.isAssociation()) {
            setCompanyTypeAssociationConfig(customer)
            return
        }

        if (customer.companyType.isLimited()) {
            setCompanyTypeLimitedConfig(customer)
            return
        }
    }

    private void setCompanyTypeAssociationConfig(Customer customer) {
        if (customer.multipleBankAccountsEnabled()) {
            bankAccountInfoService.deleteSecondaryAccountsIfExists(customer)
            CustomerFeature customerFeature = customerFeatureService.toggleMultipleBankAccounts(customer.id, false)

            if (customerFeature.hasErrors()) throw new ValidationException(null, customerFeature.errors)
        }
    }

    private void setCompanyTypeLimitedConfig(Customer customer) {
        if (customer.getConfig().dailyCheckoutLimit >= CustomerConfig.DEFAULT_CHECKOUT_DAILY_LIMIT_FOR_COMPANY_TYPE_LIMITED) return

        customerCheckoutLimitService.setDailyLimit(customer, CustomerConfig.DEFAULT_CHECKOUT_DAILY_LIMIT_FOR_COMPANY_TYPE_LIMITED, "limite diário padrão utilizado para empresa limitada")
    }

    public CustomerUpdateRequest validateNamedParams(Customer customer, Map params) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()
        CustomerValidator customerValidator = new CustomerValidator()

        if (params.containsKey("email")) {
            BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(params.email, false, customer.id)
            if (!validatedEmail.isValid()) DomainUtils.copyAllErrorsFromBusinessValidation(validatedEmail, validatedCustomerUpdateRequest)
        }

        if (params.containsKey("cpfCnpj")) {
            BusinessValidation validatedCpfCnpj = customerValidator.validateCpfCnpj(params.cpfCnpj, false, customer)
            if (!validatedCpfCnpj.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedCpfCnpj, validatedCustomerUpdateRequest)
            }
        }

        if (params.containsKey("personType")) {
            BusinessValidation validatedPersonType = customerValidator.validatePersonType(params)
            if (!validatedPersonType.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedPersonType, validatedCustomerUpdateRequest)
            }
        }

        if (params.containsKey("cityId") && !params.cityId) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar a cidade.")
        }

        BusinessValidation validatedPostalCode = customerValidator.validatePostalCode(params)
        if (!validatedPostalCode.isValid()) DomainUtils.copyAllErrorsFromBusinessValidation(validatedPostalCode, validatedCustomerUpdateRequest)

        if (params.containsKey("mobilePhone")) {
            if (!params.mobilePhone) {
                DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar um número de telefone celular.")
            } else if (!PhoneNumberUtils.validateMobilePhone(params.mobilePhone) || !PhoneNumberUtils.validateBlockListNumber(params.mobilePhone)) {
                DomainUtils.addError(validatedCustomerUpdateRequest, "O número de telefone celular não é válido.")
            }
        }

        if (params.containsKey("businessActivity")) {
            BusinessValidation validatedBusinessActivity = customerValidator.validateBusinessActivity(params.businessActivity, params.businessActivityDescription)
            if (!validatedBusinessActivity.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedBusinessActivity, validatedCustomerUpdateRequest)
            }
        }

        if (params.containsKey("incomeRange")) {
            List<AsaasError> asaasErrorList = knowYourCustomerInfoService.validateIncomeRange(customer, params)
            if (asaasErrorList) {
                DomainUtils.addError(validatedCustomerUpdateRequest, asaasErrorList.first().getMessage())
                return validatedCustomerUpdateRequest
            }
        } else if (params.containsKey("incomeValue")) {
            List<AsaasError> asaasErrorList = knowYourCustomerInfoService.validateIncomeValue(customer, params)
            if (asaasErrorList) {
                DomainUtils.addError(validatedCustomerUpdateRequest, asaasErrorList.first().getMessage())
                return validatedCustomerUpdateRequest
            }
        }

        return validatedCustomerUpdateRequest
    }

    public Boolean hasCustomerUpdateRequestChanges(Customer customer, BaseCustomer customerToBeUpdated, Map params) {
        Boolean hasAttributesChanged = DomainUtils.hasAttributesChanged(customerToBeUpdated, params)
        if (hasAttributesChanged) return true

        Boolean hasCustomerUpdateRequest = CustomerUpdateRequest.query([provider: customer, exists: true]).get()
        if (!hasCustomerUpdateRequest) return true

        Map incomeParams = [
            incomeRange: params.incomeRange?.toString(),
            incomeValue: params.incomeValue
        ]

        if (hasKnowYourCustomerInfoChanged(customer, incomeParams)) return true

        Boolean hasBusinessActivityChanged = customer.getBusinessActivity()?.id != Utils.toLong(params.businessActivity)
        return hasBusinessActivityChanged
    }

    public CriticalActionGroup saveCommercialInfoPeriodicUpdateCriticalActionGroupIfNecessary(User user, PeriodicCustomerCommercialInfoUpdateAdapter periodicCustomerCommercialInfoUpdateAdapter) {
        Customer customer = user.customer
        BaseCustomer customerToBeUpdated = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer) ?: customer
        if (!hasCustomerUpdateRequestChanges(customer, customerToBeUpdated, periodicCustomerCommercialInfoUpdateAdapter.buildUpdatableProperties())) {
            return null
        }

        return customerCriticalActionService.saveCommercialInfoPeriodicUpdateCriticalActionGroup(user)
    }

    private Map buildCustomerPropertiesMap(CustomerUpdateRequest customerUpdateRequest) {
        Map customerPropertiesMap = [:]
        customerPropertiesMap.companyType = customerUpdateRequest.companyType
        customerPropertiesMap.birthDate = customerUpdateRequest.birthDate
        customerPropertiesMap.email = customerUpdateRequest.email
        customerPropertiesMap.additionalEmails = customerUpdateRequest.additionalEmails
        customerPropertiesMap.name = customerUpdateRequest.name
        customerPropertiesMap.company = customerUpdateRequest.company
        customerPropertiesMap.responsibleName = customerUpdateRequest.responsibleName
        customerPropertiesMap.phone = customerUpdateRequest.phone
        customerPropertiesMap.mobilePhone = customerUpdateRequest.mobilePhone
        customerPropertiesMap.address = customerUpdateRequest.address
        customerPropertiesMap.addressNumber = customerUpdateRequest.addressNumber
        customerPropertiesMap.complement = customerUpdateRequest.complement
        customerPropertiesMap.province = customerUpdateRequest.province
        customerPropertiesMap.cityString = customerUpdateRequest.cityString
        customerPropertiesMap.city = customerUpdateRequest.city
        customerPropertiesMap.postalCode = customerUpdateRequest.postalCode
        customerPropertiesMap.state = customerUpdateRequest.state
        customerPropertiesMap.cpfCnpj = customerUpdateRequest.cpfCnpj
        customerPropertiesMap.personType = customerUpdateRequest.personType
        customerPropertiesMap.inscricaoEstadual = customerUpdateRequest.inscricaoEstadual
        customerPropertiesMap.site = customerUpdateRequest.site
        customerPropertiesMap.businessActivity = customerUpdateRequest.businessActivity
        customerPropertiesMap.incomeRange = customerUpdateRequest.incomeRange
        customerPropertiesMap.incomeValue = customerUpdateRequest.buildDecryptedIncomeValue()

        RevenueServiceRegister revenueServiceRegister = customerUpdateRequest.provider.getRevenueServiceRegister()
        if (revenueServiceRegister) customerPropertiesMap.companyCreationDate = revenueServiceRegister.openDate

        return customerPropertiesMap
    }

    private Map parseSaveParams(BaseCustomer customer, Map params) {
        if (params.cpfCnpj) params.cpfCnpj = Utils.removeNonNumeric(params.cpfCnpj)
        if (params.birthDate) params.birthDate = CustomDateUtils.toDate(params.birthDate)
        if (params.postalCode) params.postalCode = Utils.removeNonNumeric(params.postalCode)
        if (params.mobilePhone) {
            if (PhoneNumberUtils.isObfuscated(params.mobilePhone.toString())) {
                params.mobilePhone = customer.mobilePhone
            } else {
                params.mobilePhone = PhoneNumberUtils.sanitizeNumber(params.mobilePhone)
            }
        }
        if (params.phone) {
            if (PhoneNumberUtils.isObfuscated(params.phone.toString())) {
                params.phone = customer.phone
            } else {
                params.phone = PhoneNumberUtils.sanitizeNumber(params.phone)
            }
        }
        if (PersonType.convert(params.personType)?.isFisica()) {
            params.remove("companyType")
        }

        return params
    }

    private CustomerUpdateRequest validateSaveParams(Customer customer, Map params) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()
        List<AsaasError> asaasErrorList = []

        if (params.incomeValue) {
            asaasErrorList = knowYourCustomerInfoService.validateIncomeValue(customer, params)
        } else {
            asaasErrorList = knowYourCustomerInfoService.validateIncomeRange(customer, params)
        }

        if (asaasErrorList) {
            DomainUtils.addError(validatedCustomerUpdateRequest, asaasErrorList.first().getMessage())
            return validatedCustomerUpdateRequest
        }

        return validatedCustomerUpdateRequest
    }

    private void notifyAboutCustomerUpdateRequestedIfNecessary(Map params) {
        if (!params.shouldNotifyCustomerUpdateRequested) return
        if (!params.currentUser) return

        securityEventNotificationService.notifyAndSaveHistoryAboutCustomerUpdateRequested(params.currentUser)
    }

    private Boolean hasKnowYourCustomerInfoChanged(Customer customer, Map params) {
        if (params.incomeRange) {
            KnowYourCustomerInfoIncomeRange currentIncomeRange = KnowYourCustomerInfo.findIncomeRange(customer)
            return currentIncomeRange != KnowYourCustomerInfoIncomeRange.convert(params.incomeRange)
        }

        BigDecimal currentIncomeValue = KnowYourCustomerInfo.findIncomeValue(customer)
        return currentIncomeValue != Utils.toBigDecimal(params.incomeValue)
    }

    public String buildSaveFeedbackMessage(CustomerUpdateRequest customerUpdateRequest) {
        String messageParameter
        if (customerUpdateRequest.status.isPending()) {
            messageParameter = CustomerUpdateRequest.ESTIMATED_PENDING_PERIOD_IN_DAYS.toString()
        } else {
            messageParameter = customerUpdateRequest.denialReason
        }

        return Utils.getMessageProperty("customerUpdateRequest.message." + customerUpdateRequest.status.toString(), [messageParameter])
    }
}
