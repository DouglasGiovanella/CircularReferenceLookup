package com.asaas.service.customer

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.customer.BaseCustomer
import com.asaas.customer.CustomerValidator
import com.asaas.customer.PersonType
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.domain.planpayment.PlanPayment
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.intercom.IntercomUtils
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.plan.CustomerPlanInfoVo
import com.asaas.plan.CustomerPlanName
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerService {

    public final static List<String> COMMERCIAL_INFO_UPDATABLE_PROPERTIES = [
        "companyType", "birthDate", "email", "name", "company", "responsibleName", "phone",
        "mobilePhone", "address", "addressNumber", "complement", "province", "cityString",
        "city", "postalCode", "state", "cpfCnpj", "personType", "inscricaoEstadual", "additionalEmails",
        "site", "companyCreationDate"]

    def apiConfigService
    def asaasSegmentioService
    def beamerService
    def connectedAccountInfoHandlerService
    def customerBankSlipBeneficiaryService
    def customerBoletoBankInfoService
    def customerEventListenerService
    def customerInteractionService
    def customerPlanService
    def fraudTrackingAccountService
    def grailsApplication
    def hubspotContactService
    def hubspotEventService
    def intercomService
    def knowYourCustomerInfoService
    def modulePermissionService
    def pixTransactionCheckoutLimitService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService
    def securityEventNotificationService
    def synchronizeHermesAccountNumberService
    def userAdditionalInfoService

    public Customer findById(Long id) {
        Customer customer  = Customer.findById(id)
        if (!customer) throw new Exception("Cliente inexistente")

        return customer
    }

	public Customer updateCommercialInfo(Long customerId, Map params) {
		Customer validatedCustomer = validateCommercialInfoUpdate(customerId, params)

        if (validatedCustomer.hasErrors()) return validatedCustomer

        Customer customer = Customer.get(customerId)
        PersonType personType = customer.getPersonTypeFromCpfCnpj()
        ConnectedAccountInfoAdapter infoToBeCompared = new ConnectedAccountInfoAdapter(customer)

		customer.properties[COMMERCIAL_INFO_UPDATABLE_PROPERTIES] = params
        List<String> customerUpdatedFieldList = DomainUtils.getUpdatedFields(customer).collect { it.fieldName }

        customer.save(flush: true)

        updateBasedOnPersonType(customer)
        userAdditionalInfoService.updateUserBasedOnCustomerIfPossible(customer, [:])

        if (customer.hasErrors()) {
            throw new ValidationException("CustomerService.updateCommercialInfo >> Erro ao atualizar dados comerciais [${customerId}]", customer.errors)
        }

        knowYourCustomerInfoService.save(customer, params.incomeValue)

        knowYourCustomerInfoService.saveWithIncomeRange(customer, params.incomeRange)

        riskAnalysisRequestService.createToCustomerInfoInBlackListIfNecessary(customer)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(customer), infoToBeCompared)

        fraudTrackingAccountService.saveFraudTrackingAccountIfPossible(customer)

        synchronizeHermesAccountNumberService.update(customer)

        hubspotContactService.saveCommercialInfoUpdate(customer)

        beamerService.updateUserInformation(customer.id, [:])

        pixTransactionCheckoutLimitService.saveDefaultNightlyLimitIfNecessary(customer)

        customerEventListenerService.onCommercialInfoUpdated(customer, customerUpdatedFieldList)

        trackCustomerInfo(customer)

        if (personType != customer.getPersonTypeFromCpfCnpj()) {
            customerPlanService.updateCustomerPlanOnPersonTypeChanged(customer)
        }

        Long beneficiaryBoletoBankId = customer.boletoBank ? customer.boletoBank.id : Payment.ASAAS_ONLINE_BOLETO_BANK_ID
        customerBankSlipBeneficiaryService.createAsyncBeneficiaryRegistrationIfNecessary(beneficiaryBoletoBankId, customer)

        return customer
	}

    public Customer validateCommercialInfoUpdate(Long customerId, Map params) {
        Customer validationCustomer = validateUpdateParams(customerId, params)

        if (validationCustomer.hasErrors()) {
            return validationCustomer
        }

        Customer customer = Customer.get(customerId)

		if (!params.email) {
			DomainUtils.addError(customer, "É necessário informar o email.")
			return customer
		}

		if (params.mobilePhone && !PhoneNumberUtils.validateMobilePhone(params.mobilePhone)) {
            DomainUtils.addError(customer, "O celular informado é inválido.")
            return customer
        }

        if (!params.businessActivity) {
            DomainUtils.addError(customer, "O campo atividade comercial é obrigatório.")
            return customer
        }

        if (!params.city && !params.bypassCityValidation) {
            if (!customer.hasAccountAutoApprovalEnabled()) {
                DomainUtils.addError(customer, "É necessário informar a cidade.")
                return customer
            }
            AsaasLogger.warn("CustomerService.validateCommercialInfoUpdate >> Conta [${customerId}] com auto-aprovação habilitada e sem endereço.")
        }

        if (customer.cpfCnpj != params.cpfCnpj && params.cpfCnpj == AsaasApplicationHolder.config.asaas.cnpj.substring(0, 14)) {
            DomainUtils.addError(customer, "O CPF/CNPJ informado não é permitido.")
            return customer
        }

		return customer
	}

	private void trackCustomerInfo(Customer customer) {
		try {
			Map customerProperties = [:]

			customerProperties.name = customer.providerName
			customerProperties.email = customer.email
			customerProperties.phone = PhoneNumberUtils.buildFullPhoneNumber(customer.mobilePhone)
			customerProperties.landlinePhone = PhoneNumberUtils.buildFullPhoneNumber(customer.phone)

			asaasSegmentioService.identify(customer.id, customerProperties)
		} catch (Exception e) {
			AsaasLogger.error("CustomerService.trackCustomerInfo >> Erro ao identificar o cliente no Segmentio", e)
		}
	}

	public void setAsPotentialCustomer(Long id) {
		Customer customer = Customer.get(id)
		customer.potentialCustomer = true

		customer.save(flush: true, failOnError: true)
	}

	public void togglePotentialCustomer(Long id, Boolean potentialCustomer) {
		Customer customer = Customer.get(id)

		customer.potentialCustomer = potentialCustomer

		customer.save(flush: true, failOnError: true)
	}

	public Customer setAsNotPotentialCustomer(Long id, String reason) {
		Customer customer = Customer.get(id)

		if (!reason) {
			DomainUtils.addError(customer, "O motivo deve ser informado.")
			return customer
		}

		togglePotentialCustomer(id, false)
		customerInteractionService.save(customer, "Não é potencial cliente.\nMotivo: ${reason}")

		return customer
	}

	public Customer validateUpdateParams(Long id, params) {
		Customer validatedCustomer = new Customer()
		Customer customer = Customer.read(id)

        CustomerValidator customerValidator = new CustomerValidator()

        if (params.containsKey("cpfCnpj")) {
            BusinessValidation validatedCpfCnpj = customerValidator.validateCpfCnpj(params.cpfCnpj, true, customer)
            if (!validatedCpfCnpj.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedCpfCnpj, validatedCustomer)
            }
        }

        if (params.containsKey("email")) {
            BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(params.email, false, id)
            if (!validatedEmail.isValid()) DomainUtils.copyAllErrorsFromBusinessValidation(validatedEmail, validatedCustomer)
        }

        if (params.additionalEmails) {
            BusinessValidation validatedAdditionalEmails = customerValidator.validateAdditionalEmails(params.additionalEmails)
            if (!validatedAdditionalEmails.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedAdditionalEmails, validatedCustomer)
            }
        }

        if (params.containsKey("personType")) {
            BusinessValidation validatedPersonType = customerValidator.validatePersonType(params)
            if (!validatedPersonType.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedPersonType, validatedCustomer)
            }
        }

        if (customer.hadGeneralApproval()) {
			BusinessValidation validatedChanges = customerValidator.validateChangesAfterApproval(params, customer)
            if (!validatedChanges.isValid()) {
                DomainUtils.copyAllErrorsFromBusinessValidation(validatedChanges, validatedCustomer)
            }
        }

        BusinessValidation validatedPostalCode = customerValidator.validatePostalCode(params)
        if (!validatedPostalCode.isValid()) {
            AsaasLogger.warn("CustomerService.validateUpdateParams -> O CEP e cidade digitados pelo cliente [${customer.id}] divergem. CEP digitado: [${params.postalCode}] | Cidade digitada: [${params.city}]")
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedPostalCode, validatedCustomer)
        }

		return validatedCustomer
	}

	public Subscription findPlanSubscriptionIfExists(Customer customer) {
		Customer asaasProvider = Customer.findWhere(id: Long.valueOf(grailsApplication.config.asaas.customer.id))
		return Subscription.find("from Subscription s where s.customerAccount.provider.id = :asaasProviderId and s.customerAccount.customer.id = :currentCustomerId and s.plan.id = s.customerAccount.customer.plan.id and s.deleted = false", [asaasProviderId: asaasProvider.id, currentCustomerId: customer.id])
	}

	public Payment findLastPlanPendingPaymentIfExists(Customer customer, Subscription subscription) {
		Customer asaasProvider = Customer.findWhere(id: Long.valueOf(grailsApplication.config.asaas.customer.id))
		return Payment.executeQuery("select p from Payment p join p.subscriptionPayments sp where p.provider.id = :asaasProviderId and p.customerAccount.customer.id = :currentCustomerId and sp.subscription.id = :subscriptionId and p.status not in (:received)", [asaasProviderId: asaasProvider.id, currentCustomerId: customer.id, subscriptionId: subscription?.id, received: [PaymentStatus.CONFIRMED, PaymentStatus.RECEIVED]])[0]
	}

	public CustomerPlanInfoVo getCustomerPlanInfoVo(Customer customer) {
		Customer asaasProvider = Customer.findWhere(id: Long.valueOf(grailsApplication.config.asaas.customer.id))

        CustomerAccount customerAccount = CustomerAccount.findWhere(customer: customer, provider: asaasProvider)
		if (!customerAccount) return null

		CustomerPlanInfoVo customerPlanInfoVo = new CustomerPlanInfoVo()

		customerPlanInfoVo.customerAccount = customerAccount
		customerPlanInfoVo.currentPlan = customer.plan
		customerPlanInfoVo.currentSubscription = findPlanSubscriptionIfExists(customer)
		customerPlanInfoVo.availablePlanPaymentList = AsaasApplicationHolder.grailsApplication.mainContext.planPaymentService.listAvailable(customer)
		customerPlanInfoVo.lastPlanPendingPayment = customerPlanInfoVo.currentSubscription ? findLastPlanPendingPaymentIfExists(customer, customerPlanInfoVo.currentSubscription) : null

		for (PlanPayment planPayment in customerPlanInfoVo.availablePlanPaymentList) {
			customerPlanInfoVo.lastEndDate = planPayment.endDate
		}

        List<Long> planIdList = Plan.query([column: "id", "name[in]": CustomerPlanName.getAllCustomerPlanName()]).list()
        customerPlanInfoVo.allPaymentList = Payment.query([customerAccount: customerPlanInfoVo.customerAccount, "planId[notIn]": planIdList]).list()

		return customerPlanInfoVo
	}

	public Customer changeToRegisteredBoleto(Long customerId, Long boletoBankId, String reason, Boolean applyForAllChildAccounts) {
        BoletoBank boletoBank

        if (boletoBankId) {
            boletoBank = BoletoBank.get(boletoBankId)
            if (!boletoBank) throw new Exception("Banco do boleto [${boletoBankId}] não encontrado")
            if (!reason) throw new Exception("Motivo não informado ao associar banco de emissão de boletos")
        }

		Customer customer = Customer.get(customerId)
		if (!customer) throw new Exception("Cliente não encontrado")

        customer = updateCustomerBoletoBank(customer, boletoBank, reason)

        if (customer.hasErrors()) return customer

        if (applyForAllChildAccounts) {
            setBoletoBankForAllChildAccounts(customer, boletoBank, reason)
        }

        return customer
	}

	public Boolean hasHighDefaultRate(Customer customer) {
		Calendar startDate = Calendar.getInstance()
		startDate.setTime(new Date().clearTime())
		startDate.add(Calendar.MONTH, -1)
		startDate.set(Calendar.DAY_OF_MONTH, 1)

		Calendar finishDate = Calendar.getInstance()
		finishDate.setTime(startDate.getTime())
		finishDate.set(Calendar.DAY_OF_MONTH, finishDate.getActualMaximum(Calendar.DAY_OF_MONTH))

		int overduePaymentCount = Payment.count(customer, startDate.getTime(), finishDate.getTime(), [PaymentStatus.OVERDUE])

		if (overduePaymentCount  <= 5) return false

		int receivedPaymentCount = Payment.count(customer, startDate.getTime(), finishDate.getTime(), [PaymentStatus.RECEIVED, PaymentStatus.CONFIRMED])

		return overduePaymentCount > receivedPaymentCount
	}

    public Map providerInfoMatchesCustomerAccount(Long customerId) {
        Map returnMap = [:]
        Customer customer = Customer.get(customerId)

        if (!customer) return returnMap

        List<Long> customerIdList = [Long.valueOf(grailsApplication.config.asaas.customer.id), customerId]
        Map defaultSearch = [disableSort: true, deleted: true, column: "id", "customerId[notIn]": customerIdList]

        if (customer.company) {
            returnMap.company = CustomerAccount.query(["name[eq]": customer.company] + defaultSearch).get()
        }

        if (customer.name) {
            returnMap.name = CustomerAccount.query(["name[eq]": customer.name] + defaultSearch).get()
        }

        if (customer.email) {
            returnMap.email = CustomerAccount.query([email: customer.email] + defaultSearch).get()
        }

        if (customer.cpfCnpj) {
            returnMap.cpfCnpj = CustomerAccount.query([cpfCnpj: customer.cpfCnpj] + defaultSearch).get()
        }

        if (customer.phone) {
            returnMap.phone = CustomerAccount.query([phone: customer.phone] + defaultSearch).get()
        }

        if (customer.mobilePhone) {
            returnMap.mobilePhone = CustomerAccount.query([mobilePhone: customer.mobilePhone] + defaultSearch).get()
        }

        return returnMap
    }

    public void setDistrustLevelIfPossible(Customer customer, String distrustLevel) {
        if (!distrustLevel) return

        Integer previousDistrustLevel = customer.distrustLevel
        Integer newDistrustLevel = Integer.valueOf(distrustLevel)

        customer.distrustLevel = newDistrustLevel
        customer.save(failOnError: true)

        createRiskAnalysisForDistrustLevelIfNecessary(customer, previousDistrustLevel, newDistrustLevel)
    }

    private void createRiskAnalysisForDistrustLevelIfNecessary(Customer customer, Integer previousDistrustLevel, Integer newDistrustLevel) {
        if (!previousDistrustLevel) return

        if (newDistrustLevel <= previousDistrustLevel) return

        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CUSTOMER_DISTRUST_LEVEL_HAS_RAISED
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
    }

	public Customer setDistrustLevelIfPossible(Long id, String distrustLevel) {
		Customer customer = Customer.get(id)

		if (!customer) return

		if (!distrustLevel) {
			DomainUtils.addError(customer, "Informe um nível de desconfiança válido.")
			return customer
		}

		setDistrustLevelIfPossible(customer, distrustLevel)

		return customer
	}

    public String createApiAccessToken(User currentUser) {
        Customer customer = currentUser.customer
        if (!canCreateApiAccessToken(customer)) throw new BusinessException("Geração de API Key não autorizada.")

        intercomService.includeUserOnTag(customer.id, IntercomUtils.TAG_FOR_CREATED_API_KEY)
        hubspotEventService.trackCustomerHasCreatedApiKey(customer)
        securityEventNotificationService.notifyAndSaveHistoryAboutApiKeyCreated(currentUser)

        return executeApiAccessTokenCreation(customer)
    }

    public String executeApiAccessTokenCreation(Customer customer) {
    	String accessToken = apiConfigService.generateAccessToken(customer)

    	return accessToken
    }

    public BaseCustomer updateBasedOnPersonType(BaseCustomer baseCustomer) {
        if (baseCustomer.cpfCnpj) baseCustomer.personType = baseCustomer.getPersonTypeFromCpfCnpj()

        if (baseCustomer.personType.isFisica()) {
			baseCustomer.companyType = null
			baseCustomer.company = null
		} else {
			baseCustomer.name = baseCustomer.company
		}

        return baseCustomer.save(flush: true)
    }

    public Boolean canCreateApiAccessToken(Customer customer) {
        if (customer.boletoIsDisabled()) return false

        Boolean isAdminUser = modulePermissionService.allowed(UserUtils.getCurrentUser(), "admin")
        if (!isAdminUser) return false

        Boolean customerHasBradescoPartnerApplication = CustomerPartnerApplication.hasBradesco(customer.id)
        if (customerHasBradescoPartnerApplication) return false

        return true
    }

    private Customer updateCustomerBoletoBank(Customer customer, BoletoBank boletoBank, String reason) {
        customerBankSlipBeneficiaryService.registerBeneficiaryIfNecessary(boletoBank?.id, customer)

        customer.boletoBank = boletoBank
        customer.save(failOnError: false)

        if (customer.hasErrors()) return customer

        customerInteractionService.saveCustomerBoletoBankChange(customer.id, boletoBank?.bank, true)
        customerBoletoBankInfoService.save(customer, reason)

        return customer
    }

    private void setBoletoBankForAllChildAccounts(Customer customer, BoletoBank boletoBank, String reason) {
        List<Customer> childAccountList = Customer.childAccounts(customer, [:]).list()

        for (Customer childAccount : childAccountList) {
            updateCustomerBoletoBank(childAccount, boletoBank, reason)
        }
    }
}
