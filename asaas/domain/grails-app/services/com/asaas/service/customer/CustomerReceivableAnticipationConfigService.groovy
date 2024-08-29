package com.asaas.service.customer

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerParameterName
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigActionType
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.customerreceivableanticipationconfig.adapter.ToggleBlockBillingTypeAdapter
import com.asaas.customerreceivableanticipationconfig.adapter.UpdateCreditCardLimitAdapter
import com.asaas.customerreceivableanticipationconfig.adapter.UpdateCreditCardPercentageLimitAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerreceivableanticipationconfig.CustomerAutomaticReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigHistory
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigToggleActionHistory
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.payment.Payment
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.receivableanticipation.CustomerReceivableAnticipationConfigAdapter
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipationcompromisedvalue.ReceivableAnticipationCompromisedValueCache
import com.asaas.user.UserUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerReceivableAnticipationConfigService {

    def abTestService
    def asyncActionService
    def customerInteractionService
    def databricksService
    def grailsApplication
    def receivableAnticipationCustomerInteractionService
    def receivableAnticipationCustomerMessageService
    def receivableAnticipationValidationService
    def receivableAnticipationValidationCacheService

    public CustomerReceivableAnticipationConfig saveForNewCustomer(Customer customer) {
        if (customer.accountOwner) {
            Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.accountOwner.id]).get().asBoolean()

            if (!hasCustomerDealInfo) return save(customer, CustomerReceivableAnticipationConfigAdapter.buildByAccountOwnerConfig(customer.accountOwner))
        }

        SalesPartner salesPartner = SalesPartnerCustomer.query([customer: customer, column: 'salesPartner']).get()
        if (salesPartner) return save(customer, CustomerReceivableAnticipationConfigAdapter.buildSalesPartnerFeeConfig(customer))

        return save(customer, new CustomerReceivableAnticipationConfigAdapter())
    }

    public void drawCreditCardAnticipationFeePricingAbTestIfNecessary(Customer customer) {
        if (!shouldDrawCreditCardAnticipationFeePricingAbTest(customer)) return

        String abTestName = grailsApplication.config.asaas.abtests.creditCardAnticipationFeePricing.name
        String chosenVariant = abTestService.chooseVariant(abTestName, customer, AbTestPlatform.ALL)

        save(customer, CustomerReceivableAnticipationConfigAdapter.buildByCreditCardAnticipationFeePricingAbTestVariant(chosenVariant))
    }

    public CustomerReceivableAnticipationConfigHistory saveHistory(CustomerReceivableAnticipationConfig receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin) {
        CustomerReceivableAnticipationConfigHistory customerReceivableAnticipationConfigHistory = new CustomerReceivableAnticipationConfigHistory()

        customerReceivableAnticipationConfigHistory.receivableAnticipationConfig = receivableAnticipationConfig
        customerReceivableAnticipationConfigHistory.bankSlipLimit = receivableAnticipationConfig.bankSlipAnticipationLimit
        customerReceivableAnticipationConfigHistory.creditCardLimit = receivableAnticipationConfig.creditCardAnticipationLimit
        customerReceivableAnticipationConfigHistory.sharedCreditCardLimitEnabled = receivableAnticipationConfig.sharedCreditCardLimitEnabled
        customerReceivableAnticipationConfigHistory.creditCardPercentage = receivableAnticipationConfig.creditCardPercentage
        customerReceivableAnticipationConfigHistory.useAccountOwnerCreditCardPercentageEnabled = receivableAnticipationConfig.useAccountOwnerCreditCardPercentageEnabled
        customerReceivableAnticipationConfigHistory.creditCardDetachedDailyFee = receivableAnticipationConfig.creditCardDetachedDailyFee
        customerReceivableAnticipationConfigHistory.creditCardDetachedDailyFeeDiscount = receivableAnticipationConfig.creditCardDetachedDailyFeeDiscount
        customerReceivableAnticipationConfigHistory.creditCardInstallmentDailyFee = receivableAnticipationConfig.creditCardInstallmentDailyFee
        customerReceivableAnticipationConfigHistory.creditCardInstallmentDailyFeeDiscount = receivableAnticipationConfig.creditCardInstallmentDailyFeeDiscount
        customerReceivableAnticipationConfigHistory.bankSlipDailyFee = receivableAnticipationConfig.bankSlipDailyFee
        customerReceivableAnticipationConfigHistory.changeOrigin = changeOrigin

        return customerReceivableAnticipationConfigHistory.save(failOnError: true)
    }

    public CustomerReceivableAnticipationConfig updateBankSlipAnticipationLimit(Long customerId, BigDecimal bankSlipAnticipationLimit, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin) {
        Customer customer = Customer.get(customerId)
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)

        BigDecimal bankSlipAnticipationLimitBefore = receivableAnticipationConfig.bankSlipAnticipationLimit

        receivableAnticipationConfig.bankSlipAnticipationLimit = bankSlipAnticipationLimit
        receivableAnticipationConfig.bankSlipManualAnticipationLimit = bankSlipAnticipationLimit

        receivableAnticipationConfig.save(flush: true, failOnError: false)

        if (receivableAnticipationConfig.hasErrors()) return receivableAnticipationConfig

        customerInteractionService.saveUpdateAnticipationLimit(customer, bankSlipAnticipationLimitBefore, receivableAnticipationConfig.bankSlipAnticipationLimit)

        receivableAnticipationValidationService.onBankSlipAnticipationLimitChange(customer, bankSlipAnticipationLimitBefore)

        saveHistory(receivableAnticipationConfig, changeOrigin)

        return receivableAnticipationConfig
    }

    public void removeManualAnticipationLimit(Long customerId) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)
        receivableAnticipationConfig.bankSlipManualAnticipationLimit = null
        receivableAnticipationConfig.save(failOnError: true)
    }

    public CustomerReceivableAnticipationConfig updateCreditCardAnticipationLimit(UpdateCreditCardLimitAdapter adapter) {
        removeFromCreditCardPercentage(adapter.customer.id, adapter.attendant, adapter.changeOrigin)
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = setCreditCardAnticipationLimit(adapter.customer, adapter.attendant, adapter.newMonetaryLimit, adapter.changeOrigin)

        return customerReceivableAnticipationConfig
    }

    public CustomerReceivableAnticipationConfig setCreditCardAnticipationLimit(Customer customer, User attendant, BigDecimal creditCardAnticipationLimit, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)

        BigDecimal creditCardAnticipationLimitBefore = customerReceivableAnticipationConfig.buildCreditCardAnticipationLimit()
        if (creditCardAnticipationLimitBefore == creditCardAnticipationLimit) return DomainUtils.addError(new CustomerReceivableAnticipationConfig(), "O valor limite informado é o mesmo valor do limite atual")
        if (creditCardAnticipationLimit == null && customerReceivableAnticipationConfig.sharedCreditCardLimitEnabled) customerReceivableAnticipationConfig.sharedCreditCardLimitEnabled = false

        customerReceivableAnticipationConfig.creditCardAnticipationLimit = creditCardAnticipationLimit
        customerReceivableAnticipationConfig.save(failOnError: true)

        receivableAnticipationValidationService.onCreditCardAnticipationLimitChange(customerReceivableAnticipationConfig.customer, creditCardAnticipationLimitBefore, creditCardAnticipationLimit)

        if (!customerReceivableAnticipationConfig.hasCreditCardPercentage())  {
            receivableAnticipationCustomerInteractionService.saveUpdateCreditCardLimit(customer, attendant, creditCardAnticipationLimitBefore, creditCardAnticipationLimit)
        }

        saveHistory(customerReceivableAnticipationConfig, changeOrigin)

        return customerReceivableAnticipationConfig
    }

    public CustomerReceivableAnticipationConfig updateCreditCardPercentage(UpdateCreditCardPercentageLimitAdapter adapter) {
        CustomerReceivableAnticipationConfig validatedCustomerReceivableAnticipationConfig = validateCreditCardPercentage(adapter.customer, adapter.newPercentageLimit)
        if (validatedCustomerReceivableAnticipationConfig.hasErrors()) return validatedCustomerReceivableAnticipationConfig

        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(adapter.customer.id)

        BigDecimal previousCreditCardPercentage = customerReceivableAnticipationConfig.creditCardPercentage

        customerReceivableAnticipationConfig.creditCardPercentage = adapter.newPercentageLimit
        customerReceivableAnticipationConfig.sharedCreditCardLimitEnabled = false
        customerReceivableAnticipationConfig.save(failOnError: true)

        saveHistory(customerReceivableAnticipationConfig, adapter.changeOrigin)

        receivableAnticipationCustomerInteractionService.saveUpdateCreditCardPercentage(adapter.customer, adapter.attendant, previousCreditCardPercentage, adapter.newPercentageLimit)

        asyncActionService.saveApplyAnticipationCreditCardPercentageConfig(adapter.customer.id)

        if (adapter.newPercentageLimit > 0 && !previousCreditCardPercentage) receivableAnticipationCustomerMessageService.notifyCreditCardPercentageLimit(adapter.customer)

        return customerReceivableAnticipationConfig
    }

    public void updateAutomaticAnticipationCreditCardFeeDiscount(CustomerAutomaticReceivableAnticipationConfig config) {
        BigDecimal newFeeDiscount = (config.active) ? CustomerReceivableAnticipationConfig.AUTOMATIC_ANTICIPATION_CREDIT_CARD_DAILY_FEE_DISCOUNT : null

        CustomerReceivableAnticipationConfig anticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(config.customer.id)
        anticipationConfig.creditCardDetachedDailyFeeDiscount = newFeeDiscount
        anticipationConfig.creditCardInstallmentDailyFeeDiscount = newFeeDiscount
        anticipationConfig.save(failOnError: true)

        saveHistory(anticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.AUTOMATIC_ANTICIPATION_DISCOUNT)
    }

    public Boolean isEnabled(Long customerId) {
        return receivableAnticipationValidationCacheService.isBankSlipEnabled(customerId) || receivableAnticipationValidationCacheService.isCreditCardEnabled(customerId)
    }

    public CustomerReceivableAnticipationConfig toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter toggleBlockBillingTypeAdapter) {
        try {
            CustomerReceivableAnticipationConfig anticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(toggleBlockBillingTypeAdapter.customer.id)

            if (anticipationConfig.bankSlipEnabled == toggleBlockBillingTypeAdapter.isEnablingOperation) return anticipationConfig

            anticipationConfig.bankSlipEnabled = toggleBlockBillingTypeAdapter.isEnablingOperation
            anticipationConfig.save(failOnError: true)

            receivableAnticipationCustomerInteractionService.saveCustomerInteractionToggleBlockBillingType(toggleBlockBillingTypeAdapter, BillingType.BOLETO)

            if (anticipationConfig.bankSlipEnabled) {
                asyncActionService.saveProcessEnableBankSlipAnticipation(toggleBlockBillingTypeAdapter.customer.id)
            } else {
                asyncActionService.saveProcessDisableBankSlipAnticipation(toggleBlockBillingTypeAdapter.customer.id)
            }

            receivableAnticipationValidationCacheService.evictIsBankSlipEnabled(toggleBlockBillingTypeAdapter.customer.id)

            saveToggleActionHistory(anticipationConfig, toggleBlockBillingTypeAdapter, CustomerReceivableAnticipationConfigActionType.TOGGLE_BLOCK_BANK_SLIP_ANTICIPATION)

            return anticipationConfig
        } catch (Exception e) {
            AsaasLogger.error("CustomerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation >> Erro ao bloquear/desbloquear antecipações de boleto", e)
            throw e
        }
    }

    public CustomerReceivableAnticipationConfig toggleBlockCreditCardAnticipation(ToggleBlockBillingTypeAdapter toggleBlockBillingTypeAdapter) {
        try {
            CustomerReceivableAnticipationConfig anticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(toggleBlockBillingTypeAdapter.customer.id)

            if (anticipationConfig.creditCardEnabled == toggleBlockBillingTypeAdapter.isEnablingOperation) return anticipationConfig

            anticipationConfig.creditCardEnabled = toggleBlockBillingTypeAdapter.isEnablingOperation
            anticipationConfig.save(failOnError: true)

            receivableAnticipationCustomerInteractionService.saveCustomerInteractionToggleBlockBillingType(toggleBlockBillingTypeAdapter, BillingType.CREDIT_CARD)

            if (anticipationConfig.creditCardEnabled) {
                asyncActionService.saveProcessEnableCreditCardAnticipation(toggleBlockBillingTypeAdapter.customer.id)
            } else {
                asyncActionService.saveProcessDisableCreditCardAnticipation(toggleBlockBillingTypeAdapter.customer.id)
            }

            receivableAnticipationValidationCacheService.evictIsCreditCardEnabled(toggleBlockBillingTypeAdapter.customer.id)

            saveToggleActionHistory(anticipationConfig, toggleBlockBillingTypeAdapter, CustomerReceivableAnticipationConfigActionType.TOGGLE_BLOCK_CREDIT_CARD_ANTICIPATION)

            return anticipationConfig
        } catch (Exception e) {
            AsaasLogger.error("CustomerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation >> Erro ao bloquear/desbloquear antecipações de Cartão de Crédito", e)
            throw e
        }
    }

    public CustomerReceivableAnticipationConfig toggleSharedCreditCardLimit(Customer customer, Boolean value) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (customerReceivableAnticipationConfig.sharedCreditCardLimitEnabled == value) return customerReceivableAnticipationConfig
        if (!customerReceivableAnticipationConfig.creditCardAnticipationLimit) throw new BusinessException("Informe um valor de limite antes de aplicar essa configuração")
        if (value && customerReceivableAnticipationConfig.hasCreditCardPercentage()) throw new BusinessException("Para compartilhar o limite primeiro defina um valor de limite")

        customerReceivableAnticipationConfig.sharedCreditCardLimitEnabled = value
        customerReceivableAnticipationConfig.save(failOnError: true)

        String valueDescription = "${value ? 'habilitado' : 'desabilitado'}"

        customerInteractionService.save(customer, "Limite de antecipação de cartão de crédito compartilhado ${valueDescription}.")
        saveHistory(customerReceivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE)

        return customerReceivableAnticipationConfig
    }

    public CustomerReceivableAnticipationConfig toggleUseAccountOwnerCreditCardPercentage(Customer customer, Boolean value) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (customerReceivableAnticipationConfig.useAccountOwnerCreditCardPercentageEnabled == value) return customerReceivableAnticipationConfig
        if (value && !customerReceivableAnticipationConfig.hasCreditCardPercentage()) throw new BusinessException("Para replicar o limite primeiro defina um percentual de limite")

        customerReceivableAnticipationConfig.useAccountOwnerCreditCardPercentageEnabled = value
        customerReceivableAnticipationConfig.save(failOnError: true)

        String valueDescription = "${value ? 'habilitado' : 'desabilitado'}"

        customerInteractionService.save(customer, "Utilizar limite percentual de antecipação de cartão da conta pai nas contas filhas ${valueDescription}.")
        saveHistory(customerReceivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE)

        return customerReceivableAnticipationConfig
    }

    public void recalculateBankSlipAnticipationLimit(Customer customer) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (receivableAnticipationConfig.hasBankSlipManualAnticipationLimit()) return

        BigDecimal newLimitValue = calculateNewBankSlipAnticipationLimitValue(customer)

        BigDecimal previousAnticipationlimit = receivableAnticipationConfig.bankSlipAnticipationLimit

        receivableAnticipationConfig.bankSlipAnticipationLimit = newLimitValue
        receivableAnticipationConfig.lastBankSlipLimitRecalculationDate = new Date()
        receivableAnticipationConfig.save(flush: true, failOnError: true)

        customerInteractionService.save(customer, "Atualização automática do limite de antecipação (boletos): ${FormUtils.formatCurrencyWithMonetarySymbol(newLimitValue)}")

        receivableAnticipationValidationService.onBankSlipAnticipationLimitChange(customer, previousAnticipationlimit)

        saveHistory(receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.ANTICIPATION_LIMIT_AUTOMATIC_RECALCULATION)
    }

    public CustomerReceivableAnticipationConfig setupChildAccount(Customer customer, String parameterName, Object parameterValue) {
        if (!customer.accountOwner) throw new BusinessException(Utils.getMessageProperty("customer.dontHaveAccountOwner"))

        if (parameterName == "creditCardPercentage") {
            UpdateCreditCardPercentageLimitAdapter adapter = new UpdateCreditCardPercentageLimitAdapter(customer, Utils.toBigDecimal(parameterValue), CustomerReceivableAnticipationConfigChangeOrigin.CHILD_ACCOUNT_PARAMETER)
            return updateCreditCardPercentage(adapter)
        }

        CustomerReceivableAnticipationConfigAdapter configAdapter = CustomerReceivableAnticipationConfigAdapter.buildByChildAccountParameter(customer, parameterName, parameterValue)

        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = save(customer, configAdapter)
        saveHistory(customerReceivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.CHILD_ACCOUNT_PARAMETER)

        return customerReceivableAnticipationConfig
    }

    public CustomerReceivableAnticipationConfig validateFeeValue(Long customerId, BigDecimal feeToValidate, String fieldName) {
        CustomerReceivableAnticipationConfig validatedDomain = new CustomerReceivableAnticipationConfig()

        String fieldNameMsg = ""
        if (fieldName) fieldNameMsg = " Campo: [${Utils.getMessageProperty("receivableAnticipationFeeDescription.${fieldName}")}]."

        if (feeToValidate == null) return validatedDomain

        if (CustomerParameter.getValue(customerId, CustomerParameterName.BYPASS_FEE_VALUE_VALIDATION_FOR_NEGOTIATED_CUSTOMER)) return validatedDomain

        if (feeToValidate > CustomerReceivableAnticipationConfig.MAX_DAILY_FEE_VALUE) {
            DomainUtils.addError(validatedDomain, "A taxa diária de antecipação deve ser menor que ${CustomerReceivableAnticipationConfig.MAX_DAILY_FEE_VALUE}%.${fieldNameMsg}")
        }

        if (feeToValidate < CustomerReceivableAnticipationConfig.MIN_DAILY_FEE_VALUE) {
            DomainUtils.addError(validatedDomain, "A taxa diária de antecipação deve ser maior que ${CustomerReceivableAnticipationConfig.MIN_DAILY_FEE_VALUE}%.${fieldNameMsg}")
        }

        return validatedDomain
    }

    public CustomerReceivableAnticipationConfig validateCreditCardPercentage(Customer customer, BigDecimal creditCardPercentage) {
        return validateCreditCardPercentage(customer, creditCardPercentage, null)
    }

    public CustomerReceivableAnticipationConfig validateCreditCardPercentage(Customer customer, BigDecimal creditCardPercentage, String fieldName) {
        CustomerReceivableAnticipationConfig validatedCustomerReceivableAnticipationConfig = new CustomerReceivableAnticipationConfig()

        String fieldNameMsg = ""
        if (fieldName) fieldNameMsg = " Campo: [${Utils.getMessageProperty("receivableAnticipationFeeDescription.${fieldName}")}]."

        if (!customer) {
            DomainUtils.addError(validatedCustomerReceivableAnticipationConfig, "Cliente não encontrado.${fieldNameMsg}")
            return validatedCustomerReceivableAnticipationConfig
        }

        if (creditCardPercentage != null && creditCardPercentage < 0) {
            DomainUtils.addError(validatedCustomerReceivableAnticipationConfig, "Nenhum valor informado para alteração do percentual.${fieldNameMsg}")
            return validatedCustomerReceivableAnticipationConfig
        }

        final BigDecimal maxPercentage = 100.00
        if (creditCardPercentage >= maxPercentage) {
            DomainUtils.addError(validatedCustomerReceivableAnticipationConfig, "Valor informado para alteração do percentual deve ser menor ${maxPercentage}.${fieldNameMsg}")
            return validatedCustomerReceivableAnticipationConfig
        }

        return validatedCustomerReceivableAnticipationConfig
    }

    public void removeBankSlipAnticipationLimitForCustomerWithoutManualConfig(CustomerReceivableAnticipationConfig receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin, String description) {
        if (receivableAnticipationConfig.hasBankSlipManualAnticipationLimit()) return

        receivableAnticipationConfig.bankSlipAnticipationLimit = 0.0
        receivableAnticipationConfig.lastBankSlipLimitRecalculationDate = new Date()
        receivableAnticipationConfig.save(failOnError: true)

        saveHistory(receivableAnticipationConfig, changeOrigin)
        customerInteractionService.save(receivableAnticipationConfig.customer, description)
    }

    public BigDecimal calculateCreditCardAvailableLimit(Customer customer) {
        if (!receivableAnticipationValidationCacheService.isCreditCardEnabled(customer.id)) return 0.00

        BigDecimal creditCardAnticipationLimit = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id).buildCreditCardAnticipationLimit()
        BigDecimal notDebitedValue = ReceivableAnticipationCalculator.calculateNotDebitedValueForCreditCard(customer)
        BigDecimal availableLimit = creditCardAnticipationLimit - notDebitedValue

        return BigDecimalUtils.max(availableLimit, 0.00)
    }

    public BigDecimal calculateBankSlipAndPixAvailableLimit(Customer customer) {
        if (!receivableAnticipationValidationCacheService.isBankSlipEnabled(customer.id)) return 0.00

        BigDecimal bankSlipAndPixAnticipationLimit = CustomerReceivableAnticipationConfig.query([column: "bankSlipAnticipationLimit", customerId: customer.id]).get()
        BigDecimal availableLimit = bankSlipAndPixAnticipationLimit - ReceivableAnticipationCompromisedValueCache.getCompromisedValueForBankSlipAndPix(customer)

        return BigDecimalUtils.max(availableLimit, 0.00)
    }

    public void processBankSlipAnticipationLimitRecalculation() {
        String sql = """
        SELECT DISTINCT p.provider_id AS customerId FROM payment p
            INNER JOIN customer_receivable_anticipation_config crac
            ON p.provider_id = crac.customer_id
        WHERE p.status = :paymentStatus
            AND crac.bank_slip_manual_anticipation_limit IS NULL
            AND crac.bank_slip_enabled = :bankSlipEnabled
            AND p.payment_date >= :startDate
            AND p.payment_date <= :endDate
            AND p.deleted = :paymentDeleted
        """

        Map queryParams = [:]
        queryParams.paymentStatus = PaymentStatus.RECEIVED.toString()
        queryParams.bankSlipEnabled = true
        queryParams.startDate = CustomerReceivableAnticipationConfig.calculateStartDateToRecalculateBankSlipAnticipationLimit()
        queryParams.endDate = CustomerReceivableAnticipationConfig.calculateEndDateToRecalculateBankSlipAnticipationLimit()
        queryParams.paymentDeleted = false

        List<Long> customerIdList = databricksService.runQuery(sql, queryParams).collect { Utils.toLong(it) }

        final Integer minItemsPerThread = 15000
        final Integer batchSize = 100
        final Integer flushEvery = 50
        ThreadUtils.processWithThreadsOnDemand(customerIdList, minItemsPerThread, { List<Long> customerIdSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdSubList, batchSize, flushEvery, { Long customerId ->
                Customer customer = Customer.read(customerId)

                recalculateBankSlipAnticipationLimit(customer)
            }, [logErrorMessage: "CustomerReceivableAnticipationConfigService.processBankSlipAnticipationLimitRecalculation >> Erro ao processar atualização automática do limite de antecipação de boleto.",
                appendBatchToLogErrorMessage: true
            ])
        })
    }

    public void saveToggleActionHistory(CustomerReceivableAnticipationConfig receivableAnticipationConfig, ToggleBlockBillingTypeAdapter toggleBlockBillingTypeAdapter, CustomerReceivableAnticipationConfigActionType type) {
        CustomerReceivableAnticipationConfigToggleActionHistory customerReceivableAnticipationConfigToggleActionsHistory = new CustomerReceivableAnticipationConfigToggleActionHistory()
        customerReceivableAnticipationConfigToggleActionsHistory.receivableAnticipationConfig = receivableAnticipationConfig
        customerReceivableAnticipationConfigToggleActionsHistory.type = type
        customerReceivableAnticipationConfigToggleActionsHistory.disableReason = toggleBlockBillingTypeAdapter.disableReason
        customerReceivableAnticipationConfigToggleActionsHistory.observation = toggleBlockBillingTypeAdapter.observation
        customerReceivableAnticipationConfigToggleActionsHistory.isEnablingOperation = toggleBlockBillingTypeAdapter.isEnablingOperation
        customerReceivableAnticipationConfigToggleActionsHistory.user = UserUtils.getCurrentUser()

        customerReceivableAnticipationConfigToggleActionsHistory.save(failOnError: true)
    }

    private Boolean shouldDrawCreditCardAnticipationFeePricingAbTest(Customer customer) {
        if (!customer.isLegalPerson()) return false
        if (customer.accountOwner) return false
        if (customer.hadGeneralApproval()) return false

        CustomerReceivableAnticipationConfig currentConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        Boolean hasCustomCreditCardFee = currentConfig.creditCardDetachedDailyFee || currentConfig.creditCardInstallmentDailyFee
        if (hasCustomCreditCardFee) return false

        Boolean hasChildAccount = Customer.childAccounts(customer, [exists: true]).get().asBoolean()
        if (hasChildAccount) return false

        Boolean isWhiteLabel = CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)
        if (isWhiteLabel) return false

        Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.id]).get().asBoolean()
        if (hasCustomerDealInfo) return false

        if (CustomerPartnerApplication.hasBradesco(customer.id)) return false

        return true
    }

    public void removeBankSlipAnticipationLimitOnCustomerInactivity() {
        String sql = """
        SELECT crac.id AS configId FROM customer_receivable_anticipation_config crac
        WHERE crac.bank_slip_anticipation_limit > :bankSlipAnticipationLimit
            AND crac.bank_slip_manual_anticipation_limit IS NULL
            AND crac.bank_slip_enabled = :bankSlipEnabled
            AND NOT EXISTS (
                SELECT 1 FROM payment p
                WHERE crac.customer_id = p.provider_id
                    AND p.status = :paymentStatus
                    AND p.payment_date >= :startDate
                    AND p.payment_date <= :endDate );
        """

        Map queryParams = [:]
        queryParams.bankSlipAnticipationLimit = 0.0
        queryParams.bankSlipEnabled = true
        queryParams.paymentStatus = PaymentStatus.RECEIVED.toString()
        queryParams.startDate = CustomerReceivableAnticipationConfig.calculateStartDateToRecalculateBankSlipAnticipationLimit()
        queryParams.endDate = CustomerReceivableAnticipationConfig.calculateEndDateToRecalculateBankSlipAnticipationLimit()

        List<Long> configIdList = databricksService.runQuery(sql, queryParams).collect { Utils.toLong(it) }

        final Integer batchSize = 100
        final Integer flushEvery = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(configIdList, batchSize, flushEvery, { Long configId ->
            CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.get(configId)
            receivableAnticipationConfig.bankSlipAnticipationLimit = 0.0
            receivableAnticipationConfig.save(failOnError: true)

            saveHistory(receivableAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.ANTICIPATION_LIMIT_REMOVED_AFTER_INACTIVITY)
            customerInteractionService.save(receivableAnticipationConfig.customer, 'Limite de antecipação alterado para R$ 0,00 pois o cliente está há mais de 3 meses sem receber cobranças.')
        }, [logErrorMessage: "CustomerReceivableAnticipationConfigService.removeBankSlipAnticipationLimitOnCustomerInactivity >> Erro ao remover limite de antecipação de boleto de clientes inativos.",
            appendBatchToLogErrorMessage: true
        ])
    }

    private CustomerReceivableAnticipationConfig save(Customer customer,  CustomerReceivableAnticipationConfigAdapter configAdapter) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.query([customerId: customer.id]).get()
        if (!customerReceivableAnticipationConfig) {
            customerReceivableAnticipationConfig = new CustomerReceivableAnticipationConfig()
            customerReceivableAnticipationConfig.customer = customer
        }

        if (configAdapter?.bankSlipDailyFee) customerReceivableAnticipationConfig.bankSlipDailyFee = configAdapter.bankSlipDailyFee
        if (configAdapter?.creditCardDetachedDailyFee) customerReceivableAnticipationConfig.creditCardDetachedDailyFee = configAdapter.creditCardDetachedDailyFee
        if (configAdapter?.creditCardInstallmentDailyFee) customerReceivableAnticipationConfig.creditCardInstallmentDailyFee = configAdapter.creditCardInstallmentDailyFee
        if (configAdapter?.bankSlipEnabled != null) customerReceivableAnticipationConfig.bankSlipEnabled = configAdapter.bankSlipEnabled
        if (configAdapter?.creditCardEnabled != null) customerReceivableAnticipationConfig.creditCardEnabled = configAdapter.creditCardEnabled

        customerReceivableAnticipationConfig.save(flush: true, failOnError: true)

        return customerReceivableAnticipationConfig
    }

    private BigDecimal calculateNewBankSlipAnticipationLimitValue(Customer customer) {
        final Date startDate = CustomerReceivableAnticipationConfig.calculateStartDateToRecalculateBankSlipAnticipationLimit()
        final Date finishDate = CustomerReceivableAnticipationConfig.calculateEndDateToRecalculateBankSlipAnticipationLimit()

        BigDecimal valueSum = Payment.sumValue([customerId: customer.id,
                                                billingTypeList: [BillingType.BOLETO, BillingType.PIX, BillingType.DEPOSIT, BillingType.TRANSFER],
                                                status: PaymentStatus.RECEIVED,
                                                paymentDateStart: startDate,
                                                paymentDateFinish: finishDate,
                                                customerAccountIsNotPaymentProvider: true,
                                                "customerAndCustomerAccountCompanyPartner[notExists]": true]).get()

        BigDecimal newLimitValue = 0

        if (valueSum > 0) {
            Integer numberOfMonthsUsedInPaymentSumValue = CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(startDate, finishDate)
            Integer numberOfMonthsCustomerIsRegistered = CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(customer.dateCreated, new Date())

            BigDecimal monthAverageValue = valueSum / (Math.min(numberOfMonthsUsedInPaymentSumValue, numberOfMonthsCustomerIsRegistered) + 1)

            Integer percentageLimitForAnticipationLimit = calculatePercentageLimitForAnticipationLimit(customer)

            newLimitValue = BigDecimalUtils.calculateValueFromPercentage(monthAverageValue, percentageLimitForAnticipationLimit)
        }

        if (newLimitValue > CustomerReceivableAnticipationConfig.BANK_SLIP_ANTICIPATION_LIMIT) {
            newLimitValue = CustomerReceivableAnticipationConfig.BANK_SLIP_ANTICIPATION_LIMIT
        }

        return newLimitValue
    }

    private Integer calculatePercentageLimitForAnticipationLimit(Customer customer) {
        Date openDate = customer.getRevenueServiceRegister()?.openDate

        Calendar oneYearAgo = CustomDateUtils.getInstanceOfCalendar()
        oneYearAgo.add(Calendar.YEAR, -1)

        if (openDate && openDate >= oneYearAgo.getTime().clearTime()) {
            return CustomerReceivableAnticipationConfig.ANTICIPATION_LIMIT_PERCENTAGE_FOR_YOUNG_COMPANIES
        } else {
            return CustomerReceivableAnticipationConfig.ANTICIPATION_LIMIT_PERCENTAGE_FOR_OLDER_COMPANIES
        }
    }

    private void removeFromCreditCardPercentage(Long customerId, User attendant, CustomerReceivableAnticipationConfigChangeOrigin changeOrigin) {
        CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customerId)

        BigDecimal previousCreditCardPercentage = customerReceivableAnticipationConfig.creditCardPercentage

        customerReceivableAnticipationConfig.creditCardPercentage = null
        customerReceivableAnticipationConfig.useAccountOwnerCreditCardPercentageEnabled = false
        customerReceivableAnticipationConfig.save(failOnError: true)

        saveHistory(customerReceivableAnticipationConfig, changeOrigin)

        receivableAnticipationCustomerInteractionService.saveUpdateCreditCardPercentage(customerReceivableAnticipationConfig.customer, attendant, previousCreditCardPercentage, null)
    }
}
