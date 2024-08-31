package com.asaas.service.customer

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.customer.CustomerSignUpOriginPlatform
import com.asaas.customer.CustomerValidator
import com.asaas.customer.adapter.CustomerDefaultAdapter
import com.asaas.customersignuporigin.adapter.CustomerSignUpOriginAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.postalcode.PostalCode
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.lead.adapter.LeadDataAdapter
import com.asaas.stage.StageCode
import com.asaas.user.UserPasswordValidator
import com.asaas.user.adapter.UserAdapter
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.util.Environment
import grails.validation.ValidationException

@Transactional
class CreateCustomerService {

	def abTestService
	def accountActivationRequestService
	def accountNumberService
	def asaasSegmentioService
	def bankSlipFeeService
    def connectedAccountInfoHandlerService
    def createCampaignEventMessageService
    def createReferralService
	def customerAccountManagerService
    def customerCacheService
	def customerFeatureService
	def customerConfigService
	def customerCheckoutLimitService
	def customerFeeService
    def customerOnboardingService
    def customerParameterService
    def customerPixConfigService
    def customerPlanService
    def customerProofOfLifeService
	def customerReceivableAnticipationConfigService
	def customerRegisterStatusService
	def customerStageService
	def customerSignUpOriginService
	def customerSegmentService
	def creditCardFeeConfigService
	def criticalActionConfigService
	def feeAdminService
    def grailsApplication
    def knownYourCustomerRequestService
    def leadDataService
    def notificationDispatcherCustomerService
    def notificationDispatcherCustomerOutboxService
    def pixCreditFeeService
    def pixTransactionCheckoutLimitService
	def promotionalCodeService
	def salesPartnerCustomerService
    def sdnEntryService
	def userService
    def walletService

	public Customer save(CustomerDefaultAdapter customerDefaultAdapter) {
        Map customerParams = customerDefaultAdapter.properties

		if (!customerParams.containsKey("passwordConfirm")) customerParams.passwordConfirm = customerParams.password

		Customer customer = validateBeforeSave(customerParams)
		if (customer.hasErrors()) return customer

		customer = createCustomer(customerParams)
		if (customer.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(customer))

        UserAdapter createUserAdapter = UserAdapter.buildCreateFirstUser(customer, customerParams)
        userService.createFirstUser(createUserAdapter)

        accountActivationRequestService.saveForNewCustomer(customer, customerParams.mobilePhone, Utils.toBoolean(customerParams.enableWhiteLabel))

		if (customerParams.asaasDistinctId) {
			abTestService.aliasCustomerIfNecessary(customer, customerParams.asaasDistinctId)
		}

		customerFeatureService.save(customer)
		customerConfigService.save(customer)
        customerCheckoutLimitService.save(customer)
        pixTransactionCheckoutLimitService.saveDefaultNightlyLimitIfNecessary(customer)

        criticalActionConfigService.save(customer)

        SalesPartner salesPartner = SalesPartner.query([name: customerParams.salesPartnerName]).get()
        if (salesPartner) {
            salesPartnerCustomerService.save(customer, customerParams.salesPartnerName)
        }

        CustomerSignUpOriginAdapter customerSignUpOriginAdapter = CustomerSignUpOriginAdapter.build(customer, customerDefaultAdapter.signUpOrigin)
        AbTestPlatform abTestPlatform = customerSignUpOriginAdapter.signUpOrigin.originPlatform == CustomerSignUpOriginPlatform.WEB_MOBILE ? AbTestPlatform.WEB_MOBILE : AbTestPlatform.WEB_DESKTOP
        Boolean hasEntryPromotionVariantC = AbTestUtils.hasEntryPromotionVariantC(customer, abTestPlatform)
        if (hasEntryPromotionVariantC) promotionalCodeService.createPromotionalCodeEntryPromotionForCustomer(customer.id)

        bankSlipFeeService.saveForNewCustomer(customer, hasEntryPromotionVariantC)
		customerFeeService.saveForNewCustomer(customer)
		creditCardFeeConfigService.saveForNewCustomer(customer, hasEntryPromotionVariantC)
        pixCreditFeeService.saveForNewCustomer(customer, hasEntryPromotionVariantC)

		customerStageService.save(customer, StageCode.LEAD)
		customerReceivableAnticipationConfigService.saveForNewCustomer(customer)

        if (!salesPartner && !customer.accountOwner) {
            feeAdminService.updatePaymentMessagingNotificationFee(customer.id, CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE)
            feeAdminService.updateAlwaysChargeTransferFee(customer.id, true)
        }

        customerSignUpOriginService.save(customerSignUpOriginAdapter)

		customer.segment = customerSegmentService.getFromAccountOwnerOrInitialSegment(customer)
		customer.accountManager = customerAccountManagerService.find(customer, null)

		customer.customerRegisterStatus = customerRegisterStatusService.save(customer)

        String referralToken = customerParams.referralToken
        Long invitedByCustomerId = Utils.toLong(customerParams.customerIdFromReferralLink)

        createReferralService.processReferralOnCustomerCreation(customer, referralToken, invitedByCustomerId)

		if (customerParams.promotionalCode) promotionalCodeService.associateToCustomer(customerParams.promotionalCode, customer)

		setDefaultConfigIfChildAccount(customer)

		customerConfigService.setImmediateCheckout(customer.id)
		accountNumberService.save(customer)
        customerPixConfigService.save(customer)

        customerPlanService.createIfNotExists(customer)

        customerOnboardingService.create(customer)

		sdnEntryService.reportAboutCustomerInSDNListIfNecessary(customer)

        customerProofOfLifeService.saveForNewCustomer(customer)

		enableSandboxCustomerSettingsIfNecessary(customer)

        drawAbTestForCustomerSignedUpThroughWeb(customer)

        customer.save(flush: true)

        if (customer.accountOwner) cacheEvictToAccountOwnerWithChildAccountIfNecessary(customer.accountOwner.id)

        LeadDataAdapter leadDataAdapter = LeadDataAdapter.build(customer.email, customer.registerIp, customerParams)
        leadDataService.createLeadIfNecessary(leadDataAdapter)
        leadDataService.associateLeadWithCustomer(customer, leadDataAdapter.firstLeadUtm, leadDataAdapter.lastLeadUtm)

        walletService.save(customer)

        createCampaignEventMessageService.saveForAccountCreated(customerSignUpOriginAdapter)

		asaasSegmentioService.identify(customer.id, ["asaasSegment": customer.segment.toString()])

        knownYourCustomerRequestService.createRequestIfNecessary(customer)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(customer))

        notificationDispatcherCustomerService.saveIfNecessary(customer)
        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)

		return customer
	}

    public void drawAbTestForCustomerSignedUpThroughWebWithPersonType(Customer customer, String asaasDistinctId) {
        if (customer.isSignedUpThroughMobile()) return
        if (customer.accountOwner) return
    }

    private void drawAbTestForCustomerSignedUpThroughWeb(Customer customer) {
        if (customer.isSignedUpThroughMobile()) return
        if (customer.accountOwner) return

        abTestService.chooseVariant(grailsApplication.config.asaas.abtests.newPaymentCampaignList.name, customer, AbTestPlatform.WEB)
    }

	private Customer validateBeforeSave(Map params) {
		Customer customer = new Customer()
        CustomerValidator customerValidator = new CustomerValidator()

        BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(params.email, true)
		if (!validatedEmail.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedEmail, customer)
		}

        List<AsaasError> validateMessages = UserPasswordValidator.validatePassword(params.email, params.password, params.passwordConfirm, false)
        for (AsaasError message in validateMessages) {
            DomainUtils.addError(customer, message.getMessage())
        }

		if (params.mandatoryMobilePhone && !params.mobilePhone) {
			DomainUtils.addError(customer, "O fone celular deve ser informado.")
		}

		if (params.mobilePhone) {
            String sanitizedPhone = PhoneNumberUtils.sanitizeNumber(params.mobilePhone)
            if (!PhoneNumberUtils.validateMobilePhone(sanitizedPhone)) DomainUtils.addError(customer, "O número informado não é um número móvel válido.")
		}

		if (params.cpfCnpj && !CpfCnpjUtils.validate(params.cpfCnpj)) {
			DomainUtils.addError(customer, "O CPF/CNPJ informado é inválido, verifique os números e tente novamente.")
		}

        if (AsaasEnvironment.isSandbox()) {
            if (params.accountOwner) {
                final Integer maxChildAccountsCreatedByOwnerPerDay = 20
                Customer accountOwner = params.accountOwner
                Integer childAccountsCreatedToday = Customer.query([includeDeleted: true, accountOwner: accountOwner, 'dateCreated[ge]': new Date().clearTime()]).count()
                if (childAccountsCreatedToday > maxChildAccountsCreatedByOwnerPerDay) {
                    DomainUtils.addError(customer, "Você excedeu o limite de criação de contas filhas por dia, tente novamente amanhã.")
                }
            }
        }

		return customer
	}

    private Customer createCustomer(Map params) {
        if (params.commercialEmail) {
            params.email = params.commercialEmail
        }

        Customer customer = new Customer(params + [publicId: Customer.buildPublicId()])

        if (customer.cpfCnpj) customer.personType = customer.getPersonTypeFromCpfCnpj()

        if (!customer.city && customer.postalCode) {
            PostalCode postalCode = PostalCode.find(customer.postalCode)
            if (postalCode) customer.city = postalCode.city
        }

        customer.save(flush: true, failOnError: true)

        return customer
    }

    private void setDefaultConfigIfChildAccount(Customer customer) {
        if (!customer.accountOwner) return

        customerParameterService.save(customer, CustomerParameterName.CANNOT_USE_REFERRAL, true)
        customerParameterService.save(customer.accountOwner, CustomerParameterName.CANNOT_USE_REFERRAL, true)
    }

	private void enableSandboxCustomerSettingsIfNecessary(Customer customer) {
		if (Environment.getCurrent().equals(Environment.CUSTOM)) {
			CustomerFeature customerFeature = customerFeatureService.toggleHandleBillingInfo(customer.id, true, true, true)

			if (customerFeature.hasErrors()) throw new ValidationException(null, customerFeature.errors)
		}
	}

    private void cacheEvictToAccountOwnerWithChildAccountIfNecessary(Long accountOwnerId) {
        Boolean isAccountOwnerWithChildAccount = customerCacheService.isAccountOwnerWithChildAccount(accountOwnerId)

        if (!isAccountOwnerWithChildAccount) {
            customerCacheService.evictIsAccountOwnerWithChildAccount(accountOwnerId)
        }
    }
}
