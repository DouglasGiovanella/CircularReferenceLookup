package com.asaas.service.api

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.api.ApiAbTestParser
import com.asaas.api.ApiAccountNumberParser
import com.asaas.api.ApiAuthorizationDeviceParser
import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCustomerRegisterStatusParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiMyAccountFeesParser
import com.asaas.api.ApiProviderParser
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.checkout.CheckoutValidator
import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.customer.commercialinfoexpiration.CommercialInfoExpirationVO
import com.asaas.creditcard.CreditCardFeeConfigVO
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.Referral
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.installment.Installment
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.notification.Notification
import com.asaas.domain.notification.NotificationTrigger
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.user.User
import com.asaas.integration.intercom.IntercomUtils
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.invoice.validator.CustomerFiscalInfoValidator
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.postalservice.PaymentPostalServiceFee
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.referral.vo.ReferralPromotionBVO
import com.asaas.service.accountbalancereport.AccountBalanceReportService
import com.asaas.user.UserUtils
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiMobileAppService extends ApiBaseService {

    def abTestService
    def apiAsaasConfigService
    def apiResponseBuilderService
    def asaasCardBillPaymentRequestService
    def asaasCardService
    def creditCardService
    def creditTransferRequestService
    def customerAutomaticReceivableAnticipationConfigService
    def customerCommercialInfoExpirationService
    def customerService
    def grailsApplication
    def intercomService
    def paymentCampaignService
    def paymentWizardService
    def pixTransactionService
    def productPromotionService
    def receivableAnticipationValidationCacheService
    def referralService

    public Map asaasIndex(Map params) {
        User user = UserUtils.getCurrentUser()
        Customer customer = user.customer

        drawAnticipationOnboardingAbTestVariantIfPossible(customer)

        Map responseMap = [:]
        responseMap.config = buildAppConfig(customer, user, params)
        responseMap.abTests = ApiAbTestParser.buildAbTestsResponseItems(customer, params.supportedAbTestList)
        responseMap.fees = ApiMyAccountFeesParser.buildResponseMap(customer)
        responseMap.commercialInfo = ApiProviderParser.buildResponseItem(customer)
        responseMap.asaasConfig = apiAsaasConfigService.find(params)
        responseMap.userModules = user.getAllowedModules()

        if (!params.containsKey("shouldUpdateIntercomUserAttributes") || Utils.toBoolean(params.shouldUpdateIntercomUserAttributes)) {
            intercomService.updateCurrentUserCustomAttributes(user.id)
        }

        AccountNumber accountNumber = customer.getAccountNumber()
        responseMap.accountNumber = accountNumber ? ApiAccountNumberParser.buildResponseItem(accountNumber) : null

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map moneyIndex(Map params) {
        User user = UserUtils.getCurrentUser()
        Customer customer = user.customer

        Map responseMap = [:]
        responseMap.config = buildAppConfig(customer, user, [:])
        responseMap.abTests = ApiAbTestParser.buildAbTestsResponseItems(customer, params.supportedAbTestList)
        responseMap.commercialInfo = ApiProviderParser.buildResponseItem(customer)
        responseMap.asaasConfig = apiAsaasConfigService.find(params)
        responseMap.userModules = user.getAllowedModules()

        if (!params.containsKey("shouldUpdateIntercomUserAttributes") || Utils.toBoolean(params.shouldUpdateIntercomUserAttributes)) {
            intercomService.updateCurrentUserCustomAttributes(user.id)
        }

        AccountNumber accountNumber = customer.getAccountNumber()
        responseMap.accountNumber = accountNumber ? ApiAccountNumberParser.buildResponseItem(accountNumber) : null

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map config(Map params) {
        User user = UserUtils.getCurrentUser()
        Customer customer = getProviderInstance(params)

        Map responseMap = buildAppConfig(customer, user, [:])

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    private void drawAnticipationOnboardingAbTestVariantIfPossible(Customer customer) {
        if (AbTestUtils.validateIfCanDrawAnticipationOnboarding(customer)) {
            abTestService.chooseVariant(AsaasApplicationHolder.config.asaas.abtests.anticipationOnboarding.name, customer, AbTestPlatform.ASAAS_APP)
        }
    }

    private Map buildAppConfig(Customer customer, User user, Map params) {
        Map appConfigMap = [:]

        appConfigMap.account = buildAccountConfig(customer)
        appConfigMap.abTest = buildAbTestConfig(customer)
        appConfigMap.registerStatus = ApiCustomerRegisterStatusParser.buildResponseItem(customer)
        appConfigMap.paymentCreation = buildPaymentCreationConfig(customer)
        appConfigMap.referral = buildReferralConfig(customer)
        appConfigMap.pix = buildPixConfig(customer)
        appConfigMap.asaasCard = buildAsaasCardConfig(customer)
        appConfigMap.paymentDunning = buildPaymentDunningConfig(customer)
        appConfigMap.invoice = buildInvoiceConfig(customer)
        appConfigMap.transfer = buildTransferConfig(customer)
        appConfigMap.receivableRegistration = buildReceivableRegistrationConfig()
        appConfigMap.anticipation = buildAnticipationConfig(customer, user)
        appConfigMap.notification = buildNotificationConfig(customer)
        appConfigMap.intercom = buildIntercomConfig(customer)
        appConfigMap.accountBalanceReport = buildAccountBalanceReportConfig(customer)
        appConfigMap.commercialInfoExpiration = buildCommercialInfoExpirationConfig(user, params.appSupportsPeriodicCommercialInfoExpirationAlert)

        AuthorizationDevice authorizationDevice = AuthorizationDevice.active([customer: customer]).get()
        if (authorizationDevice) {
            appConfigMap.authorizationDevice = ApiAuthorizationDeviceParser.buildResponseItem(authorizationDevice)
        }

        return appConfigMap
    }

    private Map buildAbTestConfig(Customer customer) {
        Map abTestConfigMap = [:]

        abTestConfigMap.canDrawBillIndexView = validateIfCanDrawBillIndexView(customer)
        abTestConfigMap.canDrawDebitCardDetailView = true

        return abTestConfigMap
    }

    private Boolean validateIfCanDrawBillIndexView(Customer customer) {
        final Date BILL_VIEW_IMPROVEMENTS_RELEASE_DATE = CustomDateUtils.fromString("23/01/2024")

        return customer.dateCreated >= BILL_VIEW_IMPROVEMENTS_RELEASE_DATE
    }

    private Map buildAccountConfig(Customer customer) {
        Map accountConfigMap = [:]

        accountConfigMap.hasLargeBase = customer.hasLargeBase()
        accountConfigMap.isDisabled = customer.accountDisabled()
        accountConfigMap.isMultipleBankAccountsEnabled = customer.multipleBankAccountsEnabled()
        accountConfigMap.isBankAccountInfoApprovalNotRequired = customer.bankAccountInfoApprovalIsNotRequired()
        accountConfigMap.isBillPaymentEnabled = customer.billPaymentEnabled()
        accountConfigMap.hasConfirmedTransfer = customer.hasConfirmedTransfer()
        accountConfigMap.cannotUseReferral = customer.cannotUseReferral()
        accountConfigMap.canBeRegisteredOnIntercom = intercomService.canCustomerBeRegisteredOnIntercom(customer)
        accountConfigMap.isPaymentCampaignEnabled = !paymentCampaignService.isPaymentCampaignDisabled(customer)
        accountConfigMap.canDisplayIntegrationsFee = customerService.canCreateApiAccessToken(customer)
        accountConfigMap.hasCreatedPayments = customer.hasCreatedPayments()

        return accountConfigMap
    }

    private Map buildIntercomConfig(Customer customer) {
        Map intercomConfigMap = [:]

        intercomConfigMap.canBeRegistered = intercomService.canCustomerBeRegisteredOnIntercom(customer)
        if (intercomConfigMap.canBeRegistered) {
            String secretKey = ApiMobileUtils.isIOSAppRequest() ? grailsApplication.config.asaas.intercom.ios.identity.key : grailsApplication.config.asaas.intercom.android.identity.key

            intercomConfigMap.identityHash = IntercomUtils.buildIdentityHash(customer, secretKey)
        }

        return intercomConfigMap
    }

    private Map buildPaymentCreationConfig(Customer customer) {
        Map paymentConfigMap = [:]

        paymentConfigMap.maxPaymentValue = customer.calculateMaxPaymentValue()
        paymentConfigMap.minPaymentValue = Payment.getMinimumBankSlipAndPixValue(customer)
        paymentConfigMap.maxInstallmentCount = Installment.getMaxInstallmentCount(customer)
        paymentConfigMap.maxInstallmentCountForCreditCard = Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD
        paymentConfigMap.minCreditCardPaymentValue = Payment.getMinimumDetachedCreditCardValue(customer)
        paymentConfigMap.minCreditCardSubscriptionValue = Payment.getMinimumDetachedCreditCardValue(customer)
        paymentConfigMap.campaignMaxInstallmentCount = Installment.getMaxInstallmentCount(customer)
        paymentConfigMap.bankSlipDueDateLimitDaysOptions = PaymentCampaign.DUE_DATE_LIMIT_DAYS_OPTIONS
        paymentConfigMap.daysToReceiveBankSlip = CustomerConfig.buildDaysToReceiveBankSlip(customer)
        paymentConfigMap.daysToReceiveCreditCard = creditCardService.buildDaysToReceiveCreditCard(customer)
        paymentConfigMap.daysToReceiveDebitCard = grailsApplication.config.payment.debitCard.daysToCredit
        paymentConfigMap.hasAlternativeTermToCreatePayment = AbTestUtils.hasAlternativeTermToCreatePayment(customer)

        paymentConfigMap.monthlyFreePaymentCount = paymentWizardService.getFreePaymentsAmountIfHasMinimumForShowValueWithoutFee(customer)

        return paymentConfigMap
    }

    private Map buildAnticipationConfig(Customer customer, User user) {
        Map config = [:]

        config.canAnticipate = customer.canAnticipate()
        config.hasAlreadyAnticipated = ReceivableAnticipation.query([customer: customer, exists: true]).get().asBoolean()

        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        config.canDisplayAnticipationFee = !receivableAnticipationConfig.operationFee.asBoolean()
        config.creditCardEnabled = receivableAnticipationConfig.creditCardEnabled
        config.bankSlipEnabled = receivableAnticipationConfig.bankSlipEnabled
        config.pixEnabled = receivableAnticipationConfig.bankSlipEnabled
        config.hasAnticipationPercentage = receivableAnticipationConfig.hasCreditCardPercentage()
        config.requiresConfirmedTransferToAnticipate = ReceivableAnticipationValidator.requiresConfirmedTransferToAnticipate(customer)

        config.isEligibleForPromotion = productPromotionService.isEligibleForReceivableAnticipationPromotion(user)
        config.totalValueAvailableForAnticipation = CustomerStatistic.getTotalValueAvailableForAnticipation(customer)
        config.businessDaysToAnalyze = ReceivableAnticipation.BUSINESS_DAYS_TO_ANALYZE
        config.bankSlipBusinessDaysToAnalyze = ReceivableAnticipation.BANK_SLIP_BUSINESS_DAYS_TO_ANALYZE
        config.creditCardBusinessDaysToAnalyze = ReceivableAnticipation.CREDIT_CARD_BUSINESS_DAYS_TO_ANALYZE
        config.pixBusinessDaysToAnalyze = ReceivableAnticipation.PIX_BUSINESS_DAYS_TO_ANALYZE

        config.hasAutomaticAnticipation = customerAutomaticReceivableAnticipationConfigService.hasAutomaticReceivableAnticipation(customer)
        config.hasAutomaticAnticipationActivated = receivableAnticipationValidationCacheService.isAutomaticActivated(customer.id)

        config.isEligibleForPromotionWithSpecialFees = customerAutomaticReceivableAnticipationConfigService.isCustomerEligibleToDiscount(customer)

        Date firstConfirmedTransferDate = ReceivableAnticipation.findFirstConfirmedTransferDate(customer)
        config.daysToEnableBankSlipAnticipation = ReceivableAnticipationCalculator.calculateDaysToEnableBankSlipAnticipation(customer, firstConfirmedTransferDate)
        config.daysToEnableCreditCardAnticipation = ReceivableAnticipationCalculator.calculateDaysToEnableCreditCardAnticipation(customer, firstConfirmedTransferDate)

        return config
    }

    private Map buildReceivableRegistrationConfig() {
        Map config = [:]

        config.businessDaysToReceiveContestationReply = CercContestation.BUSINESS_DAYS_TO_RECEIVE_A_REPLY

        return config
    }

    private Map buildTransferConfig(Customer customer) {
        Map config = [:]

        config.minValue = CreditTransferRequest.MINIMUM_VALUE
        config.minValueWithoutFee = CreditTransferRequest.MINIMUM_VALUE_WITHOUT_TRANSFER_FEE
        config.alwaysChargeFee = CustomerFee.getAlwaysChargeTransferFee(customer.id)
        config.limitHourToExecuteTransferOnSameDay = CreditTransferRequest.getLimitHourToExecuteTransferToday()
        config.maximumScheduleDate = creditTransferRequestService.calculateDefaultMaximumScheduledDate()
        config.canSelectOperationType = true

        return config
    }

    private Map buildInvoiceConfig(Customer customer) {
        Map config = [:]
        config.daysBeforePaymentDueDateForEffectiveDate = Invoice.DAYS_LIST_BEFORE_PAYMENT_DUE_DATE_FOR_EFFECTIVE_DATE
        config.isEnabled = CustomerFeature.isInvoiceEnabled(customer.id)

        if (config.isEnabled) {
            CustomerFiscalInfoValidator customerFiscalInfoValidator = new CustomerFiscalInfoValidator(customer.id)
            config.hasValidFiscalInfo = customerFiscalInfoValidator.validate() && customerFiscalInfoValidator.validateIfAnyCredentialsHasBeenSent()
        } else {
            config.hasValidFiscalInfo = false
        }

        return config
    }

    private Map buildPixConfig(Customer customer) {
        Map config = [:]

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        config.canUsePix = checkoutValidator.customerCanUsePix()

        config.maxPortabilityDays = PixUtils.ADDRESS_KEY_MAX_PORTABILITY_DAYS
        config.maxOwnershipClaimDays = PixUtils.ADDRESS_KEY_MAX_OWNERSHIP_CLAIM_DAYS
        config.personTypeFisicaMaxKeys = PixUtils.NATURAL_PERSON_MAX_KEYS
        config.personTypeJuridicaMaxKeys = PixUtils.LEGAL_PERSON_MAX_KEYS
        config.debitFeeValue = CustomerFee.calculatePixDebitFee(customer)
        config.staticQrCodeAddressKeyAndDescriptionMaxLength = PixUtils.STATIC_QR_CODE_ADDRESS_KEY_AND_DESCRIPTION_MAX_LENGTH
        config.monthlyTransactionsCountWithoutChargeFee = CustomerCheckoutFee.getMonthlyTransfersWithoutFeeConfig(customer).quantity
        config.minimumDaytimeToCreateTransaction = PixTransactionCheckoutLimit.INITIAL_DAYTIME_PERIOD
        config.maximumDaytimeToCreateTransaction = PixTransactionCheckoutLimit.getInitialNightlyHourConfig(customer)
        config.maximumScheduleDate = pixTransactionService.calculateDefaultMaximumSingleScheduledDate()
        config.canCreateUnlimitedPixDebitWithoutFee = CustomerCheckoutFee.customerCanCreateUnlimitedPixDebitWithoutFee(customer)
        config.canReceivePaymentWithPix = PixUtils.paymentReceivingWithPixEnabled(customer)

        return config
    }

    private Map buildAsaasCardConfig(Customer customer) {
        asaasCardService.enableEloIfNecessary(customer)

        Customer asaasCustomer = asaasCardBillPaymentRequestService.getAsaasCreditCardBillPaymentProvider()

        Map asaasCardConfig = [:]
        asaasCardConfig.isEnabled = !customer.asaasCardDisabled()
        asaasCardConfig.preApprovedCreditLimit = asaasCardService.getCustomerCreditLimit(customer.id)
        asaasCardConfig.totalFreeDebitCards = AsaasCard.TOTAL_FREE_DEBIT_ELO_CARDS
        asaasCardConfig.totalFreePrepaidCardsLegalPerson = AsaasCard.TOTAL_FREE_PREPAID_ELO_CARDS_LEGAL_PERSON
        asaasCardConfig.totalFreePrepaidCardsNaturalPerson = AsaasCard.TOTAL_FREE_PREPAID_ELO_CARDS_NATURAL_PERSON
        asaasCardConfig.creditBillMinimumAccountDebtPayment = AsaasCardBillPayment.MINIMUM_VALUE
        asaasCardConfig.creditBillMinimumBankSlipAndPixPayment = Payment.getMinimumBankSlipAndPixValue(asaasCustomer)

        return asaasCardConfig
    }

    private Map buildPaymentDunningConfig(Customer customer) {
        Map configMap = [:]

        configMap.minOverdueDays = PaymentDunning.MINIMUM_OVERDUE_DAYS

        configMap.typeFeeList = [
            [type: PaymentDunningType.CREDIT_BUREAU.toString(), feeValue: CustomerFee.getDunningCreditBureauFeeValue(customer.id)]
        ]

        return configMap
    }

    private Map buildReferralConfig(Customer customer) {
        Map referralMap = [:]

        referralMap.bankSlipFee = buildReferralBankSlipFeeMap(customer)
        referralMap.creditCardFee = buildReferralCreditCardFee(customer)
        referralMap.discountPeriodInMonths = BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS
        referralMap.remainingReferrals = referralService.getRemainingReferralCount(customer)
        referralMap.invitationUrl = referralService.buildReferralUrl(customer)
        referralMap.hasSomeInviteSent = Referral.query([invitedByCustomer: customer, column: "id"]).get().asBoolean()
        referralMap.monthlyInvitationLimit = Referral.MONTHLY_INVITATION_LIMIT
        referralMap.invitedCustomerDiscountValue = AsaasApplicationHolder.config.asaas.referral.promotionalCode.discountValue.invited

        referralMap.promotion = AbTestUtils.hasReferralPromotionVariantB(customer) ? buildReferralPromotionMap() : null

        return referralMap
    }

    private Map buildReferralPromotionMap() {
        ReferralPromotionBVO promotion = referralService.buildReferralPromotionBVO()

        Map promotionMap = [:]

        promotionMap.firstNaturalPersonIndicationDiscountValue = promotion.firstNaturalPersonIndicationDiscountValue
        promotionMap.secondNaturalPersonIndicationDiscountValue = promotion.secondNaturalPersonIndicationDiscountValue
        promotionMap.overTwoNaturalPersonIndicationDiscountValue = promotion.overTwoNaturalPersonIndicationDiscountValue
        promotionMap.firstLegalPersonIndicationDiscountValue = promotion.firstLegalPersonIndicationDiscountValue
        promotionMap.secondLegalPersonIndicationDiscountValue = promotion.secondLegalPersonIndicationDiscountValue
        promotionMap.overTwoLegalPersonIndicationDiscountValue = promotion.overTwoLegalPersonIndicationDiscountValue
        promotionMap.maximumFeeDiscountValuePerMonth = promotion.maximumFeeDiscountValuePerMonth

        return promotionMap
    }

    private Map buildReferralBankSlipFeeMap(Customer customer) {
        BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer)
        Map bankSlipFeeMap = [:]
        bankSlipFeeMap.defaultValue = bankSlipFee.defaultValue
        bankSlipFeeMap.discountValueInvited = BigDecimal.valueOf(grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited)

        if (bankSlipFee.discountExpiration) {
            bankSlipFeeMap.remainingDays = CustomDateUtils.calculateDifferenceInDays(new Date().clearTime(), bankSlipFee.discountExpiration)
        }

        if (bankSlipFee.discountValue) {
            bankSlipFeeMap.discountValue = bankSlipFee.discountValue
        } else {
            bankSlipFeeMap.discountValue = bankSlipFee.calculateDiscountValue()
        }

        return bankSlipFeeMap
    }

    private Map buildReferralCreditCardFee(Customer customer) {
        Map referralCreditCardFeeMap = [:]

        CreditCardFeeConfigVO creditCardFeeConfigVO = new CreditCardFeeConfigVO(customer)
        referralCreditCardFeeMap.discountUpfrontFee = creditCardFeeConfigVO.discountUpfrontFee
        referralCreditCardFeeMap.fixedFee = creditCardFeeConfigVO.fixedFee

        return referralCreditCardFeeMap
    }

    private Map buildNotificationConfig(Customer customer) {
        Map notificationConfig = [:]

        notificationConfig.isWhatsappEnabled = CustomerFeature.isWhatsappNotificationEnabled(customer.id)
        notificationConfig.paymentOverdueOffsetOptions = NotificationTrigger.listScheduleOffsetForCustomerPaymentOverdue()
        notificationConfig.paymentDueDateWarningOffsetOptions = NotificationTrigger.listDaysBeforeToSendPaymentDueDateWarning()
        notificationConfig.maxNotificationsForOverduePayments = Notification.MAX_NOTIFICATIONS_FOR_OVERDUE_PAYMENTS
        notificationConfig.isPaymentSmsNotificationFeeEnabled = CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_PAYMENT_SMS_NOTIFICATION_FEE).asBoolean()
        notificationConfig.postalServiceAverageDeliveryPeriodInDays = PaymentPostalServiceFee.POSTAL_SERVICE_AVERAGE_DELIVERY_PERIOD_IN_DAYS

        return notificationConfig
    }

    private Map buildAccountBalanceReportConfig(Customer customer) {
        Map accountBalanceReportConfig = [:]

        Boolean appSupportsNewAccountBalanceReport = ApiMobileUtils.appSupportsNewAccountBalanceReport()
        if (appSupportsNewAccountBalanceReport) {
            accountBalanceReportConfig.canBeDisplayed = customer.personType?.isFisica() && CustomDateUtils.getYear(customer.dateCreated) <= AccountBalanceReportService.RELEASE_YEAR
        }

        return accountBalanceReportConfig
    }

    private Map buildCommercialInfoExpirationConfig(User currentUser, Boolean appSupportsPeriodicCommercialInfoExpirationAlert) {
        CommercialInfoExpirationVO commercialInfoExpiration = customerCommercialInfoExpirationService.buildCommercialInfoExpirationAlertData(currentUser)

        if (!shouldShowInfoExpirationAlert(commercialInfoExpiration, appSupportsPeriodicCommercialInfoExpirationAlert)) return null

        Map commercialInfoExpirationConfigMap = [:]

        commercialInfoExpirationConfigMap.shouldShowMandatoryInfoExpirationAlert = commercialInfoExpiration.shouldShowMandatoryInfoExpirationAlert
        commercialInfoExpirationConfigMap.shouldShowPeriodicExpirationAlert = commercialInfoExpiration.shouldShowPeriodicExpirationAlert
        commercialInfoExpirationConfigMap.daysToShowNextAlert = commercialInfoExpiration.daysToShowNextAlert
        commercialInfoExpirationConfigMap.adminEmails = commercialInfoExpiration.adminUsernameList
        commercialInfoExpirationConfigMap.deadlineDate = ApiBaseParser.formatDate(commercialInfoExpiration.scheduledDate)
        commercialInfoExpirationConfigMap.isAccessBlocked = commercialInfoExpiration.isExpired
        commercialInfoExpirationConfigMap.isLegalPerson = commercialInfoExpiration.isLegalPerson

        return commercialInfoExpirationConfigMap
    }

    private Boolean shouldShowInfoExpirationAlert(CommercialInfoExpirationVO commercialInfoExpiration, Boolean appSupportsPeriodicCommercialInfoExpirationAlert) {
        Boolean shouldShowPeriodicExpirationAlert = commercialInfoExpiration.shouldShowPeriodicExpirationAlert && appSupportsPeriodicCommercialInfoExpirationAlert

        return shouldShowPeriodicExpirationAlert || commercialInfoExpiration.shouldShowMandatoryInfoExpirationAlert
    }
}
