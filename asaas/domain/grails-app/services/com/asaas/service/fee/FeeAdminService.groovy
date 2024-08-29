package com.asaas.service.fee

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.credit.CreditType
import com.asaas.customer.CustomerParameterName
import com.asaas.customerdealinfo.validator.CustomerDealInfoValidator
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.credit.Credit
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.exception.CustomerNotFoundException
import com.asaas.feeConfiguration.adapter.FeeConfigurationInfoUpdateAdapter
import com.asaas.feeConfiguration.validator.FeeDiscountPeriodValidator
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCreditFeeType
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeAdminService {

    def asyncActionService
    def bankSlipFeeService
    def creditCardFeeConfigAdminService
    def creditCardFeeConfigService
    def creditService
    def customerConfigService
    def customerFeeService
    def customerInteractionService
    def customerReceivableAnticipationConfigService
    def feeAdminChildAccountReplicationService
    def notificationService
    def pixCreditFeeService
    def receivableAnticipationCustomerInteractionService

    public BankSlipFee updateBankSlipFee(Long customerId, BigDecimal defaultValue, BigDecimal discountValue, Date discountExpiration, Boolean applyForAllChildAccounts) {
        Customer customer = Customer.get(customerId)

        BankSlipFee bankSlipFee = bankSlipFeeService.update(customer, defaultValue, discountValue, discountExpiration, false)
        if (bankSlipFee.hasErrors()) return bankSlipFee

        if (applyForAllChildAccounts) {
            BankSlipFee validatedDomain = feeAdminChildAccountReplicationService.validateSetBankSlipFeeManuallyForChildAccounts(customerId)
            if (validatedDomain.hasErrors()) return validatedDomain

            feeAdminChildAccountReplicationService.setBankSlipFeeManuallyForChildAccounts(customerId, defaultValue, discountValue, discountExpiration)
        }

        return bankSlipFee
    }

    public CustomerFee updateFee(Long customerId, Map feeConfig, Boolean applyForAllChildAccounts) {
        Customer customer = Customer.get(customerId)

        CustomerFee customerFee = CustomerFee.find(customer)
        if (!customerFee) customerFee = new CustomerFee()
        if (!validateFee(customerFee, feeConfig)) return customerFee

        customerInteractionService.setCustomerInteractionFee(customer, feeConfig, false)

        customerFee = customerFeeService.save(customer, feeConfig)

        if (applyForAllChildAccounts) {
            CustomerFee validatedDomain = feeAdminChildAccountReplicationService.validateSetCustomerFeeManuallyForChildAccounts(customerId, feeConfig)
            if (validatedDomain.hasErrors()) return validatedDomain

            feeAdminChildAccountReplicationService.setCustomerFeeManuallyForChildAccounts(customer.id, feeConfig)
        }

        return customerFee
    }

    public PixCreditFee updatePixCreditFee(Long customerId, PixCreditFeeType pixCreditFeeType, Map pixCreditFeeConfig, Boolean applyForAllChildAccounts) {
        Customer customer = Customer.get(customerId)
        PixCreditFee pixCreditFee

        if (pixCreditFeeType.isFixed()) {
            BigDecimal fixedFee = Utils.toBigDecimal(pixCreditFeeConfig.fixedFee)
            BigDecimal fixedFeeWithDiscount = Utils.toBigDecimal(pixCreditFeeConfig.fixedFeeWithDiscount)
            Date discountExpiration = CustomDateUtils.toDate(pixCreditFeeConfig.discountExpiration)

            pixCreditFee = pixCreditFeeService.saveFixedFee(customer, fixedFee, fixedFeeWithDiscount, discountExpiration, false, true)
        } else if (pixCreditFeeType.isPercentage()) {
            BigDecimal percentageFee = Utils.toBigDecimal(pixCreditFeeConfig.percentageFee)
            BigDecimal minimumFee = Utils.toBigDecimal(pixCreditFeeConfig.minimumFee)
            BigDecimal maximumFee = Utils.toBigDecimal(pixCreditFeeConfig.maximumFee)

            pixCreditFee = pixCreditFeeService.savePercentageFee(customer, percentageFee, minimumFee, maximumFee, false, true)
        }

        if (applyForAllChildAccounts) {
            PixCreditFee validatedPixCreditFee = feeAdminChildAccountReplicationService.validateSetPixCreditFeeForChildAccounts(customer.id)
            if (validatedPixCreditFee.hasErrors()) return validatedPixCreditFee

            feeAdminChildAccountReplicationService.setPixCreditFeeForChildAccounts(customer, pixCreditFee)
        }

        return pixCreditFee
    }

    public CreditCardFeeConfig updateCreditCardFee(Long customerId, Map feeConfig, Boolean applyForAllChildAccounts) {
        Customer customer = Customer.get(customerId)

        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.query([customerId: customerId]).get()
        if (!creditCardFeeConfig) {
            creditCardFeeConfig = creditCardFeeConfigService.save(customer, creditCardFeeConfigService.buildFeeConfigWithoutDiscount())
        }

        CreditCardFeeConfig validatedCreditCardFeeConfig = creditCardFeeConfigAdminService.validateCreditCardFee(customerId, creditCardFeeConfig, feeConfig)
        if (validatedCreditCardFeeConfig.hasErrors()) return validatedCreditCardFeeConfig

        creditCardFeeConfigService.save(customer, feeConfig)
        customerInteractionService.saveUpdateCreditCardFee(customer, feeConfig, false)

        if (applyForAllChildAccounts) {
            CreditCardFeeConfig validatedDomain = feeAdminChildAccountReplicationService.validateSetCreditCardFeeManuallyForChildAccounts(customerId)
            if (validatedDomain.hasErrors()) return validatedDomain

            feeAdminChildAccountReplicationService.setCreditCardFeeManuallyForChildAccounts(customerId, feeConfig)
        }

        return creditCardFeeConfig
    }

    public CustomerReceivableAnticipationConfig updateReceivableAnticipationConfigFee(Long customerId, BigDecimal creditCardDetachedDailyFeeValue, BigDecimal creditCardInstallmentDailyFeeValue, BigDecimal bankSlipDailyFeeValue, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin) {
        CustomerReceivableAnticipationConfig validatedCustomerReceivableAnticipationConfig = validateCustomerReceivableAnticipationConfig(creditCardDetachedDailyFeeValue, creditCardInstallmentDailyFeeValue, bankSlipDailyFeeValue, customerId)
        if (validatedCustomerReceivableAnticipationConfig.hasErrors()) return validatedCustomerReceivableAnticipationConfig

        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)

        Map receivableAnticipationConfigFee = [
                creditCardDetachedDailyFeeBefore: customerReceivableAnticipationConfig.creditCardDetachedDailyFee,
                creditCardDetachedDailyFeeAfter: creditCardDetachedDailyFeeValue,
                creditCardInstallmentDailyFeeBefore: customerReceivableAnticipationConfig.creditCardInstallmentDailyFee,
                creditCardInstallmentDailyFeeAfter: creditCardInstallmentDailyFeeValue,
                bankSlipDailyFeeBefore: customerReceivableAnticipationConfig.bankSlipDailyFee,
                bankSlipDailyFeeAfter: bankSlipDailyFeeValue,
        ]
        receivableAnticipationCustomerInteractionService.saveUpdateCustomerReceivableAnticipationConfigFee(customerReceivableAnticipationConfig.customer, receivableAnticipationConfigFee)

        customerReceivableAnticipationConfig.creditCardDetachedDailyFee = creditCardDetachedDailyFeeValue
        customerReceivableAnticipationConfig.creditCardInstallmentDailyFee = creditCardInstallmentDailyFeeValue
        customerReceivableAnticipationConfig.bankSlipDailyFee = bankSlipDailyFeeValue
        customerReceivableAnticipationConfig.save(failOnError: true)

        customerReceivableAnticipationConfigService.saveHistory(customerReceivableAnticipationConfig, changeOrigin)

        return customerReceivableAnticipationConfig
    }

    public CustomerFee updatePaymentMessagingNotificationFee(Long customerId, BigDecimal paymentMessagingNotificationFeeValue) {
        Map feeConfig = [ paymentMessagingNotificationFeeValue: paymentMessagingNotificationFeeValue ]
        updateFee(customerId, feeConfig, false)
    }

    public CustomerFee updateAlwaysChargeTransferFee(Long customerId, Boolean alwaysChargeTransferFee) {
        Map feeConfig = [ alwaysChargeTransferFee: alwaysChargeTransferFee ]
        updateFee(customerId, feeConfig, false)
    }

    public void updateAllFeesToDefaultValuesManually(FeeConfigurationInfoUpdateAdapter feeConfigurationAdapter) {
        BusinessValidation validatedBusiness = validateUpdateAllFeesToDefaultValuesManually(feeConfigurationAdapter)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        updateAllFeesToDefaultValues(feeConfigurationAdapter.customer, "solicitação do time de Experiência do cliente para retornar cliente às taxas padrões")
    }

    public void updateAllFeesToDefaultValues(Customer customer, String customerInteractionReason) {
        customerInteractionService.save(customer, "Início da alteração de taxas após ${customerInteractionReason}", null)

        Date currentBankSlipDiscountExpiration = BankSlipFee.query([column: "discountExpiration", customer: customer]).get() ?: null
        BigDecimal bankSlipDiscountValue = currentBankSlipDiscountExpiration ? BankSlipFee.DISCOUNT_BANK_SLIP_FEE : null
        bankSlipFeeService.update(customer, BankSlipFee.DEFAULT_BANK_SLIP_FEE, bankSlipDiscountValue, currentBankSlipDiscountExpiration, false)

        Date currentPixCreditDiscountExpiration = PixCreditFee.query([column: "discountExpiration", customer: customer]).get() ?: null
        BigDecimal pixCreditDiscountValue = currentPixCreditDiscountExpiration ? PixCreditFee.DISCOUNT_PIX_FEE : null
        pixCreditFeeService.saveFixedFee(customer, PixCreditFee.DEFAULT_PIX_FEE, pixCreditDiscountValue, currentPixCreditDiscountExpiration, false, true)

        customerConfigService.setImmediateCheckout(customer.id)

        creditCardFeeConfigService.save(customer, creditCardFeeConfigService.buildFeeConfigWithoutDiscount())

        updatePaymentSmsNotificationFee(customer, CustomerFee.PAYMENT_SMS_NOTIFICATION_FEE_VALUE)

        updateReceivableAnticipationConfigFee(customer.id, null, null, null, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE_TO_DEFAULT_FEE)

        Map customerFeeConfig = [:]
        customerFeeConfig.paymentMessagingNotificationFeeValue = CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE
        customerFeeConfig.alwaysChargeTransferFee = true
        customerFeeConfig.invoiceValue = CustomerFee.SERVICE_INVOICE_FEE
        customerFeeConfig.productInvoiceValue = CustomerFee.PRODUCT_INVOICE_FEE
        customerFeeConfig.consumerInvoiceValue = CustomerFee.CONSUMER_INVOICE_FEE
        customerFeeConfig.transferValue = CustomerFee.CREDIT_TRANSFER_FEE_VALUE
        customerFeeConfig.pixDebitFee = CustomerFee.PIX_DEBIT_FEE
        customerFeeConfig.creditBureauReportNaturalPersonFee = CustomerFee.CREDIT_BUREAU_REPORT_NATURAL_PERSON_FEE
        customerFeeConfig.creditBureauReportLegalPersonFee = CustomerFee.CREDIT_BUREAU_REPORT_LEGAL_PERSON_FEE
        customerFeeConfig.dunningCreditBureauFeeValue = CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE
        customerFeeConfig.whatsappNotificationFee = CustomerFee.WHATSAPP_FEE
        customerFeeConfig.childAccountKnownYourCustomerFee = CustomerFee.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_FEE
        customerFeeConfig.phoneCallNotificationFee = CustomerFee.PHONE_CALL_NOTIFICATION_FEE
        updateFee(customer.id, customerFeeConfig, false)
    }

    public void setFreeAsaasCustomerFee(Long asaasCustomerId, User currentUser) {
        Customer asaasCustomer = Customer.read(asaasCustomerId)

        if (!asaasCustomer) throw new CustomerNotFoundException("Cliente [${asaasCustomerId}] não encontrado.")

        if (!CpfCnpjUtils.isAsaasCnpj(asaasCustomer.cpfCnpj)) {
            throw new BusinessException("Somente contas com cnpj do Asaas podem ter o zeramento de taxas.")
        }

        setFreeCustomerFee(asaasCustomer)
        setFreeCreditCardFee(asaasCustomerId)
        notificationService.disableAllPhoneCallNotifications(asaasCustomer, currentUser.email)
        customerInteractionService.save(asaasCustomer, "Realizado o zeramento das taxas da conta.", currentUser)
    }

    public BusinessValidation validateUpdateAllFeesToDefaultValuesManuallyByCustomer(Customer customer) {
        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

        FeeConfigurationInfoUpdateAdapter feeConfigurationInfoUpdateAdapter = new FeeConfigurationInfoUpdateAdapter(customer, pixCreditFee)

        return validateUpdateAllFeesToDefaultValuesManually(feeConfigurationInfoUpdateAdapter)
    }

    public BusinessValidation validateUpdateAllFeesToDefaultValuesManually(FeeConfigurationInfoUpdateAdapter feeConfigurationAdapter) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (hasAllFeesDefaultValues(feeConfigurationAdapter)) {
            validatedBusiness.addError("feeConfiguration.updateAllFeesToDefaultValues.allFeesHaveDefaultValues")
            return validatedBusiness
        }

        BusinessValidation hasCustomerDealBusinessValidation = CustomerDealInfoValidator.validateCustomerHasNegotiation(feeConfigurationAdapter.customer)
        if (!hasCustomerDealBusinessValidation.isValid()) return hasCustomerDealBusinessValidation

        return validatedBusiness
    }

    public void updateNewInternalUserFees(Long customerId) {
        updateBankSlipFee(customerId, CustomerFee.ASAAS_COLLABORATOR_FEE, null, null, false)

        Map pixCreditFeeConfig = [fixedFee: CustomerFee.ASAAS_COLLABORATOR_FEE, fixedFeeWithDiscount: null, discountExpiration: null]
        updatePixCreditFee(customerId, PixCreditFeeType.FIXED, pixCreditFeeConfig, false)
    }

    public void applyBankSlipFeeDiscountPeriod(Long bankSlipFeeId, Date discountExpiration) {
        BankSlipFee bankSlipFee = BankSlipFee.get(bankSlipFeeId)

        BankSlipFee validatedBankSlipFee = FeeDiscountPeriodValidator.validateApplyBankSlipFeeDiscountPeriod(bankSlipFee, discountExpiration)
        if (validatedBankSlipFee.hasErrors()) throw new ValidationException(null, validatedBankSlipFee.errors)

        BigDecimal discountValue = bankSlipFee.discountValue ?: BankSlipFee.DISCOUNT_BANK_SLIP_FEE

        bankSlipFee = updateBankSlipFee(bankSlipFee.customer.id, bankSlipFee.defaultValue, discountValue, discountExpiration, false)
        if (bankSlipFee.hasErrors()) throw new ValidationException(null, bankSlipFee.errors)
    }

    public void applyPixCreditFeeDiscountPeriod(Long pixCreditFeeId, Date discountExpiration) {
        PixCreditFee pixCreditFee = PixCreditFee.get(pixCreditFeeId)

        PixCreditFee validatedPixCreditFee = FeeDiscountPeriodValidator.validateApplyPixCreditFeeDiscountPeriod(pixCreditFee, discountExpiration)
        if (validatedPixCreditFee.hasErrors()) throw new ValidationException(null, validatedPixCreditFee.errors)

        Map pixCreditFeeConfig = Utils.bindPropertiesFromDomainClass(pixCreditFee, [])
        pixCreditFeeConfig.discountExpiration = discountExpiration
        pixCreditFeeConfig.fixedFeeWithDiscount = pixCreditFee.fixedFeeWithDiscount ?: PixCreditFee.DISCOUNT_PIX_FEE

        pixCreditFee = updatePixCreditFee(pixCreditFee.customer.id, pixCreditFee.type, pixCreditFeeConfig, false)
        if (pixCreditFee.hasErrors()) throw new ValidationException(null, pixCreditFee.errors)
    }

    public void applyCreditCardFeeDiscountPeriod(Long creditCardFeeConfigId, Date discountExpiration) {
        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.get(creditCardFeeConfigId)

        CreditCardFeeConfig validatedCreditCardFeeConfig = FeeDiscountPeriodValidator.validateCreditCardFeeDiscountPeriod(creditCardFeeConfig, discountExpiration)
        if (validatedCreditCardFeeConfig.hasErrors()) throw new ValidationException(null, validatedCreditCardFeeConfig.errors)

        Map creditCardFeeConfigInfo = Utils.bindPropertiesFromDomainClass(creditCardFeeConfig, [])
        creditCardFeeConfigInfo.discountExpiration = discountExpiration
        creditCardFeeConfigInfo.discountUpfrontFee = creditCardFeeConfig.discountUpfrontFee ?: CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_FEE
        creditCardFeeConfigInfo.discountUpToSixInstallmentsFee = creditCardFeeConfig.discountUpToSixInstallmentsFee ?: CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE
        creditCardFeeConfigInfo.discountUpToTwelveInstallmentsFee = creditCardFeeConfig.discountUpToTwelveInstallmentsFee ?: CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE

        creditCardFeeConfig = updateCreditCardFee(creditCardFeeConfig.customer.id, creditCardFeeConfigInfo, false)
        if (creditCardFeeConfig.hasErrors()) throw new ValidationException(null, creditCardFeeConfig.errors)
    }

    public Boolean generateAllCustomerCreditForIncorrectlyPixFeeCharged() {
        Map asyncActionData = asyncActionService.getPending(AsyncActionType.GENERATE_CUSTOMER_CREDIT_FOR_INCORRECTLY_FEE_CHARGED)
        if (!asyncActionData) return false

        Utils.withNewTransactionAndRollbackOnError({
            Long accountOwnerId = asyncActionData.accountOwnerId
            Long lastChildAccountProcessedId = asyncActionData.lastChildAccountProcessedId

            Map search = [:]
            search.column = "id"
            search.accountOwnerId = accountOwnerId
            if (lastChildAccountProcessedId) search."id[gt]" = lastChildAccountProcessedId
            final Integer maxItemsPerCycle = 100
            List<Long> childAccountIdList = Customer.notDisabledAccounts(search).list(max: maxItemsPerCycle)

            if (!childAccountIdList) {
                asyncActionService.delete(asyncActionData.asyncActionId)
                return
            }

            generateCustomerCreditForIncorrectlyPixFeeCharged(childAccountIdList)

            asyncActionService.delete(asyncActionData.asyncActionId)

            saveNextAsyncAction(accountOwnerId, childAccountIdList.last())
        }, [logErrorMessage: "feeAdminService.generateCustomerCreditForIncorrectlyBankSlipAndPixFeeCharged >> Erro ao executar asyncActionId: [${asyncActionData.asyncActionId}]",
            onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])

        return true
    }

    public Map buildCreditCardDefaultFeeConfig() {
        Map feeConfig = [:]
        feeConfig.fixedFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FIXED_FEE
        feeConfig.upfrontFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FEE
        feeConfig.upToSixInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE
        feeConfig.upToTwelveInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE

        feeConfig.discountExpiration = null
        feeConfig.discountUpfrontFee = null
        feeConfig.discountUpToSixInstallmentsFee = null
        feeConfig.discountUpToTwelveInstallmentsFee = null

        return feeConfig
    }

    private void generateCustomerCreditForIncorrectlyPixFeeCharged(List<Long> childAccountIdList) {
        Date firstDateWithError = CustomDateUtils.fromString("2024-06-27", CustomDateUtils.DATABASE_DATE_FORMAT)

        Utils.forEachWithFlushSession(childAccountIdList, 5, { Long childAccountId ->
            Utils.withNewTransactionAndRollbackOnError({
                BigDecimal creditValue = calculateValueForPixFee(childAccountId, firstDateWithError)

                if (!creditValue) return

                Date jobStartDate = CustomDateUtils.fromString("2024-07-15", CustomDateUtils.DATABASE_DATE_FORMAT)
                Boolean hasCreditForCustomer = Credit.query([customer: Customer.read(childAccountId), "lastUpdated[ge]": jobStartDate, type: CreditType.PIX_FEE_REFUND]).get().asBoolean()

                if (hasCreditForCustomer) throw new BusinessException("Já foi lançado esse tipo de crédito para o cliente ${childAccountId}")

                creditService.save(childAccountId, CreditType.PIX_FEE_REFUND, "Estorno ref. a taxa de pix", creditValue, null)
            }, [
                ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.error("FeeAdminService.generateCustomerCreditForIncorrectlyPixFeeCharged -> Erro ao gerar credito para cliente ${childAccountId}", exception)
                }]
            )
        })
    }

    private BigDecimal calculateValueForPixFee(Long childAccountId, Date firstDateWithError) {
        BigDecimal childAccountPixFixedFeeValue = PixCreditFee.query([column: "fixedFee", customer: Customer.read(childAccountId)]).get()

        Map search = [:]
        search.customerId = childAccountId
        search."transactionDate[ge]" = firstDateWithError
        search.paymentBillingType = BillingType.PIX
        search.transactionType = FinancialTransactionType.PAYMENT_FEE
        search."value[ne]" = childAccountPixFixedFeeValue * -1
        search.column = "value"

        List<BigDecimal> financialTransactionValueList = FinancialTransaction.query(search).list()

        if (!financialTransactionValueList) return 0

        BigDecimal pixFeeTotalValueCharged = 0
        for (BigDecimal value : financialTransactionValueList) {
            pixFeeTotalValueCharged += BigDecimalUtils.abs(value)
        }

        BigDecimal pixFeeTotalValueOwed = financialTransactionValueList.size() * childAccountPixFixedFeeValue

        if (pixFeeTotalValueCharged > pixFeeTotalValueOwed) {
            return pixFeeTotalValueCharged - pixFeeTotalValueOwed
        } else {
            AsaasLogger.warn("FeeAdminService.calculateValueForPixFee >> Valor cobrado inferior ao valor devido para o cliente: ${childAccountId} - ${pixFeeTotalValueCharged} ${pixFeeTotalValueOwed}")
            return 0
        }
    }

    private void saveNextAsyncAction(Long accountOwnerId, Long lastChildAccountProcessedId) {
        Map actionData = [accountOwnerId: accountOwnerId, lastChildAccountProcessedId: lastChildAccountProcessedId]
        asyncActionService.save(AsyncActionType.GENERATE_CUSTOMER_CREDIT_FOR_INCORRECTLY_FEE_CHARGED, actionData)
    }

    private Boolean hasAllFeesDefaultValues(FeeConfigurationInfoUpdateAdapter feeConfigurationAdapter) {
        if (!hasBankSlipFeeDefaultValue(feeConfigurationAdapter.bankSlipFee)) return false

        if (!hasPixCreditFeeDefaultValue(feeConfigurationAdapter.pixCreditFee)) return false

        if (!hasCustomerReceivableAnticipationConfigDefaultValue(feeConfigurationAdapter.receivableAnticipationConfig)) return false

        if (!hasCreditCardFeeDefaultValue(feeConfigurationAdapter.creditCardFeeConfig)) return false

        return hasCustomerFeeDefaultValue(feeConfigurationAdapter.customerFee)
    }

    private Boolean hasBankSlipFeeDefaultValue(BankSlipFee bankSlipFee) {
        if (!bankSlipFee) return false
        if (bankSlipFee.defaultValue != BankSlipFee.DEFAULT_BANK_SLIP_FEE) return false
        if (bankSlipFee.discountExpiration && !bankSlipFee.discountValue) return false
        if (bankSlipFee.discountValue && bankSlipFee.discountValue != BankSlipFee.DISCOUNT_BANK_SLIP_FEE) return false

        return true
    }

    private Boolean hasPixCreditFeeDefaultValue(PixCreditFee pixCreditFee) {
        if (!pixCreditFee) return false

        if (pixCreditFee.fixedFee != PixCreditFee.DEFAULT_PIX_FEE) return false
        if (pixCreditFee.discountExpiration && !pixCreditFee.fixedFeeWithDiscount) return false
        if (pixCreditFee.fixedFeeWithDiscount && pixCreditFee.fixedFeeWithDiscount != PixCreditFee.DISCOUNT_PIX_FEE) return false

        return true
    }

    private Boolean hasCustomerReceivableAnticipationConfigDefaultValue(CustomerReceivableAnticipationConfig receivableAnticipationConfig) {
        if (!receivableAnticipationConfig) return false

        BigDecimal creditCardDetachedDailyFee = receivableAnticipationConfig.creditCardDetachedDailyFee
        BigDecimal creditCardInstallmentDailyFee = receivableAnticipationConfig.creditCardInstallmentDailyFee
        BigDecimal bankSlipDailyFee = receivableAnticipationConfig.bankSlipDailyFee
        if (creditCardDetachedDailyFee && creditCardDetachedDailyFee != CustomerReceivableAnticipationConfig.getDefaultCreditCardDetachedDailyFee()) return false
        if (creditCardInstallmentDailyFee && creditCardInstallmentDailyFee != CustomerReceivableAnticipationConfig.getDefaultCreditCardInstallmentDailyFee()) return false
        if (bankSlipDailyFee && bankSlipDailyFee != CustomerReceivableAnticipationConfig.getDefaultBankSlipDailyFee()) return false

        return true
    }

    private Boolean hasCreditCardFeeDefaultValue(CreditCardFeeConfig creditCardFeeConfig) {
        if (!creditCardFeeConfig) return false

        if (creditCardFeeConfig.fixedFee != CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FIXED_FEE) return false
        if (creditCardFeeConfig.upfrontFee != CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FEE) return false
        if (creditCardFeeConfig.upToSixInstallmentsFee != CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE) return false
        if (creditCardFeeConfig.upToTwelveInstallmentsFee != CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE) return false

        if (creditCardFeeConfig.debitCardFixedFee != CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FIXED_FEE) return false
        if (creditCardFeeConfig.debitCardFee != CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FEE) return false

        return true
    }

    private hasCustomerFeeDefaultValue(CustomerFee customerFee) {
        if (customerFee.paymentMessagingNotificationFeeValue != CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE) return false
        if (!customerFee.alwaysChargeTransferFee) return false
        if (customerFee.invoiceValue != CustomerFee.SERVICE_INVOICE_FEE) return false
        if (customerFee.productInvoiceValue != CustomerFee.PRODUCT_INVOICE_FEE) return false
        if (customerFee.consumerInvoiceValue != CustomerFee.CONSUMER_INVOICE_FEE) return false
        if (customerFee.transferValue != CustomerFee.CREDIT_TRANSFER_FEE_VALUE) return false
        if (customerFee.pixDebitFee != CustomerFee.PIX_DEBIT_FEE) return false
        if (customerFee.creditBureauReportNaturalPersonFee != CustomerFee.CREDIT_BUREAU_REPORT_NATURAL_PERSON_FEE) return false
        if (customerFee.creditBureauReportLegalPersonFee != CustomerFee.CREDIT_BUREAU_REPORT_LEGAL_PERSON_FEE) return false
        if (customerFee.dunningCreditBureauFeeValue != CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE) return false
        if (customerFee.whatsappNotificationFee != CustomerFee.WHATSAPP_FEE) return false
        if (customerFee.childAccountKnownYourCustomerFee != CustomerFee.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_FEE) return false
        if (customerFee.phoneCallNotificationFee != CustomerFee.PHONE_CALL_NOTIFICATION_FEE) return false

        return true
    }

    private Boolean isValidFeeValue(def feeData) {
        if (feeData.key == "paymentMessagingNotificationFeeValue") return true

        final List<String> allowedZeroableValueFeeList = ["pixDebitFee", "childAccountKnownYourCustomerFee", "transferValue"]
        if (allowedZeroableValueFeeList.contains(feeData.key)) {
            if (feeData.value == null) return false
            return true
        }

        if (!feeData.value && !(feeData.value instanceof Boolean)) return false

        return true
    }

    private Boolean validateFee(oldObjectFee, Map newFee) {
        if (newFee.any { !isValidFeeValue(it) }) {
            DomainUtils.addError(oldObjectFee, "Informe um valor válido.")
            return false
        }

        if (!oldObjectFee) return true

        return true
    }

    private CustomerReceivableAnticipationConfig validateCustomerReceivableAnticipationConfig(BigDecimal creditCardDetachedDailyFeeValue, BigDecimal creditCardInstallmentDailyFeeValue, BigDecimal bankSlipDailyFeeValue, Long customerId) {
        CustomerReceivableAnticipationConfig validatedCustomerReceivableAnticipationConfig = new CustomerReceivableAnticipationConfig()

        List<BigDecimal> feeValuesList = [
                creditCardDetachedDailyFeeValue,
                creditCardInstallmentDailyFeeValue,
                bankSlipDailyFeeValue
        ]

        for (BigDecimal feeValue : feeValuesList) {
            CustomerReceivableAnticipationConfig validatedFeeValue = customerReceivableAnticipationConfigService.validateFeeValue(customerId, feeValue, null)
            DomainUtils.copyAllErrorsFromObject(validatedFeeValue, validatedCustomerReceivableAnticipationConfig)
        }

        return validatedCustomerReceivableAnticipationConfig
    }

    private void setFreeCustomerFee(Customer asaasCustomer) {
        CustomerFee customerFee = CustomerFee.find(asaasCustomer)
        customerFee.transferValue = 0.00
        customerFee.whatsappNotificationFee = 0.00
        customerFee.paymentMessagingNotificationFeeValue = 0.00
        customerFee.alwaysChargeTransferFee = false
        customerFee.pixDebitFee = 0.00
        customerFee.childAccountKnownYourCustomerFee = 0.00
        customerFee.save(failOnError: true)
    }

    private void setFreeCreditCardFee(Long asaasCustomerId) {
        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.query([customerId: asaasCustomerId]).get()
        creditCardFeeConfig.fixedFee = 0.00
        creditCardFeeConfig.upfrontFee = 0.00
        creditCardFeeConfig.upToSixInstallmentsFee = 0.00
        creditCardFeeConfig.upToTwelveInstallmentsFee = 0.00
        creditCardFeeConfig.debitCardFee = 0.00
        creditCardFeeConfig.debitCardFixedFee = 0.00
        creditCardFeeConfig.discountUpfrontFee = null
        creditCardFeeConfig.discountUpToSixInstallmentsFee = null
        creditCardFeeConfig.discountUpToTwelveInstallmentsFee = null
        creditCardFeeConfig.discountExpiration = null
        creditCardFeeConfig.save(failOnError: true)
    }

    private void updatePaymentSmsNotificationFee(Customer customer, BigDecimal paymentSmsNotificationFeeValue) {
        Boolean hasPaymentSmsNotificationFeeEnabled = CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_PAYMENT_SMS_NOTIFICATION_FEE).asBoolean()
        if (!hasPaymentSmsNotificationFeeEnabled) return

        Map feeConfig = [paymentSmsNotificationFeeValue: paymentSmsNotificationFeeValue]
        updateFee(customer.id, feeConfig, false)
    }
}
