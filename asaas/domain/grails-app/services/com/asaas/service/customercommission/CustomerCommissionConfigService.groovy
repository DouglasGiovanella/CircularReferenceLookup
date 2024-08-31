package com.asaas.service.customercommission

import com.asaas.customercommission.CustomerCommissionConfigType
import com.asaas.customercommissionconfig.repository.CustomerCommissionConfigRepository
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customercommission.CustomerCommissionConfigQueueInfo
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.service.accountowner.ChildAccountParameterBinderService
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import grails.validation.ValidationException

@GrailsCompileStatic
@Transactional
class CustomerCommissionConfigService {

    ChildAccountParameterBinderService childAccountParameterBinderService

    public CustomerCommissionConfig savePixCommissionConfig(Long accountOwnerId, CustomerCommissionConfigType commissionType, BigDecimal value, BigDecimal maximumValue, BigDecimal minimumValue) {
        CustomerCommissionConfig validatedDomain = validatePixCommissionConfig(commissionType, value, minimumValue, maximumValue)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)

        if (commissionType == CustomerCommissionConfigType.FIXED_FEE) {
            customerCommissionConfig.pixFeeFixedValueWithOverprice = value
            customerCommissionConfig.pixFeeFixedValue = null

            customerCommissionConfig.pixFeePercentageWithOverprice = null
            customerCommissionConfig.pixMaximumFee = null
            customerCommissionConfig.pixMinimumFee = null
        } else if (commissionType == CustomerCommissionConfigType.PERCENTAGE) {
            customerCommissionConfig.pixFeeFixedValueWithOverprice = null
            customerCommissionConfig.pixFeeFixedValue = null

            customerCommissionConfig.pixFeePercentageWithOverprice = value
            customerCommissionConfig.pixMaximumFee = maximumValue
            customerCommissionConfig.pixMinimumFee = minimumValue
        }

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, PixCreditFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST, PixCreditFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    @Deprecated
    public CustomerCommissionConfig saveBankSlipCommissionConfigWithoutOverPice(Long accountOwnerId, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateBankSlipCommissionConfig(CustomerCommissionConfigType.FIXED_FEE, value, accountOwnerId)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)

        customerCommissionConfig.bankSlipFeePercentage = null
        customerCommissionConfig.bankSlipFeeFixedValueWithOverprice = null
        customerCommissionConfig.bankSlipFeeFixedValue = value

        return customerCommissionConfig.save(failOnError: true)
    }

    @Deprecated
    public CustomerCommissionConfig savePixFixedCommissionConfigWithoutOverprice(Long accountOwnerId, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validatePixCommissionConfig(CustomerCommissionConfigType.FIXED_FEE, value, null, null)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)

        customerCommissionConfig.pixFeeFixedValue = value
        customerCommissionConfig.pixFeeFixedValueWithOverprice = null

        customerCommissionConfig.pixFeePercentageWithOverprice = null
        customerCommissionConfig.pixMaximumFee = null
        customerCommissionConfig.pixMinimumFee = null

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, PixCreditFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST, PixCreditFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveBankSlipCommissionConfig(Long accountOwnerId, CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateBankSlipCommissionConfig(commissionType, value, accountOwnerId)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        Boolean needApplyChildAccountParameter = false

        if (commissionType == CustomerCommissionConfigType.FIXED_FEE) {
            customerCommissionConfig.bankSlipFeePercentage = null
            customerCommissionConfig.bankSlipFeeFixedValueWithOverprice = value
            customerCommissionConfig.bankSlipFeeFixedValue = null
            needApplyChildAccountParameter = true

        } else if (commissionType == CustomerCommissionConfigType.PERCENTAGE) {
            if (customerCommissionConfig.bankSlipFeeFixedValueWithOverprice) needApplyChildAccountParameter = true

            customerCommissionConfig.bankSlipFeePercentage = value
            customerCommissionConfig.bankSlipFeeFixedValue = null
            customerCommissionConfig.bankSlipFeeFixedValueWithOverprice = null
        }

        if (needApplyChildAccountParameter) childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, BankSlipFee.OVERPRICE_COMMISSIONABLE_FIELD_LIST, BankSlipFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveCreditCardFeeCommissionConfig(Long accountOwnerId, BigDecimal commissionPercentage, BigDecimal commissionUpToSixPercentage, BigDecimal commissionUpToTwelvePercentage) {
        CustomerCommissionConfig validatedDomain = validateCreditCardCommissionConfig(commissionPercentage, commissionUpToSixPercentage, commissionUpToTwelvePercentage)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        customerCommissionConfig.creditCardPaymentFeePercentageWithOverprice = commissionPercentage
        customerCommissionConfig.creditCardPaymentFeeUpToSixPercentageWithOverprice = commissionUpToSixPercentage
        customerCommissionConfig.creditCardPaymentFeeUpToTwelvePercentageWithOverprice = commissionUpToTwelvePercentage

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, CreditCardFeeConfig.COMMISSIONABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST, CreditCardFeeConfig.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveCreditCardReceivableAnticipationCommissionConfig(Long accountOwnerId, BigDecimal creditCardDetachedDailyPercentage, BigDecimal creditCardInstallmentDailyPercentage) {
        CustomerCommissionConfig validatedDomain = validateDailyReceivableAnticipationCommissionConfig(creditCardDetachedDailyPercentage, "creditCardDetachedAnticipationPercentageWithOverprice")
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        validatedDomain = validateDailyReceivableAnticipationCommissionConfig(creditCardInstallmentDailyPercentage, "creditCardInstallmentAnticipationPercentageWithOverprice")
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        customerCommissionConfig.creditCardDetachedAnticipationPercentageWithOverprice = creditCardDetachedDailyPercentage
        customerCommissionConfig.creditCardInstallmentAnticipationPercentageWithOverprice = creditCardInstallmentDailyPercentage

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, CustomerReceivableAnticipationConfig.CREDIT_CARD_OVERPRICE_COMMISSIONABLE_FIELD_LIST, CustomerReceivableAnticipationConfig.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveBankSlipReceivableAnticipationCommissionConfig(Long accountOwnerId, BigDecimal bankSlipDailyPercentage) {
        CustomerCommissionConfig validatedDomain = validateDailyReceivableAnticipationCommissionConfig(bankSlipDailyPercentage, "bankSlipAnticipationPercentageWithOverprice")
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        customerCommissionConfig.bankSlipAnticipationPercentageWithOverprice = bankSlipDailyPercentage

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(accountOwnerId, CustomerReceivableAnticipationConfig.BANK_SPLIP_OVERPRICE_COMMISSIONABLE_FIELD_LIST, CustomerReceivableAnticipationConfig.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig savePaymentDunningFeeCommissionConfig(Long accountOwnerId, CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validatePaymentDunningFeeConfig(commissionType, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        Boolean needApplyChildAccountParameter = false

        if (commissionType.isPercentage()) {
            if (customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice) needApplyChildAccountParameter = true

            customerCommissionConfig.paymentDunningFeePercentage = value
            customerCommissionConfig.paymentDunningFeeFixedValue = null
            customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice = null
        } else {
            customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice = value
            customerCommissionConfig.paymentDunningFeeFixedValue = null
            customerCommissionConfig.paymentDunningFeePercentage = null

            needApplyChildAccountParameter = true
        }

        if (needApplyChildAccountParameter) childAccountParameterBinderService.applyParameterForAllChildAccounts(accountOwnerId, "dunningCreditBureauFeeValue", CustomerFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    @Deprecated
    public CustomerCommissionConfig savePaymentDunningFeeCommissionConfigWithoutOverprice(Long accountOwnerId, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validatePaymentDunningFeeConfig(CustomerCommissionConfigType.FIXED_FEE, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)

        customerCommissionConfig.paymentDunningFeeFixedValue = value
        customerCommissionConfig.dunningCreditBureauFeeFixedValueWithOverprice = null
        customerCommissionConfig.paymentDunningFeePercentage = null

        childAccountParameterBinderService.applyParameterForAllChildAccounts(accountOwnerId, "dunningCreditBureauFeeValue", CustomerFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveInvoiceCommissionConfig(Long accountOwnerId, CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateInvoiceCommissionConfig(commissionType, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        Boolean needApplyChildAccountParameter = false

        if (commissionType == CustomerCommissionConfigType.FIXED_FEE) {
            customerCommissionConfig.invoiceFeePercentage = null
            customerCommissionConfig.invoiceFeeFixedValue = null
            customerCommissionConfig.invoiceFeeFixedValueWithOverprice = value
            needApplyChildAccountParameter = true
        } else {
            if (customerCommissionConfig.invoiceFeeFixedValueWithOverprice) needApplyChildAccountParameter = true

            customerCommissionConfig.invoiceFeePercentage = value
            customerCommissionConfig.invoiceFeeFixedValue = null
            customerCommissionConfig.invoiceFeeFixedValueWithOverprice = null
        }

        if (needApplyChildAccountParameter) childAccountParameterBinderService.applyParameterForAllChildAccounts(accountOwnerId, "invoiceValue", CustomerFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    @Deprecated
    public CustomerCommissionConfig saveInvoiceFixedCommissionConfigWithoutOverprice(Long accountOwnerId, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateInvoiceCommissionConfig(CustomerCommissionConfigType.FIXED_FEE, value)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)

        customerCommissionConfig.invoiceFeeFixedValue = value
        customerCommissionConfig.invoiceFeeFixedValueWithOverprice = null
        customerCommissionConfig.invoiceFeePercentage = null

        childAccountParameterBinderService.applyParameterForAllChildAccounts(accountOwnerId, "invoiceValue", CustomerFee.simpleName)

        return customerCommissionConfig.save(failOnError: true)
    }

    public CustomerCommissionConfig saveDebitCardCommissionConfig(Long accountOwnerId, BigDecimal commissionPercentage) {
        CustomerCommissionConfig validatedDomain = validateDebitCardCommissionConfig(commissionPercentage)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerCommissionConfig customerCommissionConfig = findOrCreateCommissionConfig(accountOwnerId)
        customerCommissionConfig.debitCardFeePercentage = commissionPercentage

        return customerCommissionConfig.save(failOnError: true)
    }

    private CustomerCommissionConfig findOrCreateCommissionConfig(Long accountOwnerId) {
        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfigRepository.query([customerId: accountOwnerId]).disableSort().get()
        if (customerCommissionConfig) return customerCommissionConfig

        Customer accountOwner = Customer.get(accountOwnerId)
        customerCommissionConfig = new CustomerCommissionConfig()
        customerCommissionConfig.customer = accountOwner
        customerCommissionConfig.save(failOnError: true)

        CustomerCommissionConfigQueueInfo customerCommissionConfigQueueInfo = new CustomerCommissionConfigQueueInfo()
        customerCommissionConfigQueueInfo.customerCommissionConfig = customerCommissionConfig
        customerCommissionConfigQueueInfo.save(failOnError: true)

        return customerCommissionConfig
    }

    private CustomerCommissionConfig validateCommissionConfig(CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        if (!commissionType) {
            DomainUtils.addError(validatedDomain, "É necessário informar o tipo de comissionamento")
            return validatedDomain
        }

        if (Utils.isEmptyOrNull(value)) {
            DomainUtils.addError(validatedDomain, "É necessário informar o valor do comissionamento")
            return validatedDomain
        }

        if (value < CustomerCommissionConfig.MIN_COMMISSION_VALUE) {
            String message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.MIN_COMMISSION_VALUE)
            DomainUtils.addError(validatedDomain, "O valor precisa ser superior a ${message}")
            return validatedDomain
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validatePixCommissionConfig(CustomerCommissionConfigType commissionType, BigDecimal value, BigDecimal minimumValue, BigDecimal maximumValue) {
        CustomerCommissionConfig validatedDomain = validateCommissionConfig(commissionType, value)
        if (validatedDomain.hasErrors()) return validatedDomain

        if (commissionType.isPercentage()) {
            return validatePixFeePercentage(value, minimumValue, maximumValue)
        } else {
            return validatePixFeeFixedValue(value)
        }
    }

    private CustomerCommissionConfig validatePixFeePercentage(BigDecimal pixFeePercentage, BigDecimal minimumValue, BigDecimal maximumValue) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message

        if (pixFeePercentage > CustomerCommissionConfig.PIX_COMMISSION_MAX_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.PIX_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (pixFeePercentage < CustomerCommissionConfig.PIX_COMMISSION_MIN_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.PIX_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        if (minimumValue && maximumValue) {
            if (minimumValue > maximumValue) {
                return DomainUtils.addError(validatedDomain, "O valor mínimo não pode ser maior que o máximo.") as CustomerCommissionConfig
            }
        } else {
            return DomainUtils.addError(validatedDomain, "Deve ser preenchido um valor mínimo e máximo.") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validatePixFeeFixedValue(BigDecimal pixFeeFixedValue) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message
        if (pixFeeFixedValue > CustomerCommissionConfig.PIX_COMMISSION_MAX_FIXED_FEE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.PIX_COMMISSION_MAX_FIXED_FEE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (pixFeeFixedValue < CustomerCommissionConfig.PIX_COMMISSION_MIN_FIXED_FEE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.PIX_COMMISSION_MIN_FIXED_FEE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateBankSlipCommissionConfig(CustomerCommissionConfigType commissionType, BigDecimal value, Long accountOwnerId) {
        CustomerCommissionConfig validatedDomain = validateCommissionConfig(commissionType, value)
        if (validatedDomain.hasErrors()) return validatedDomain

        if (commissionType.isPercentage()) {
            return validateBankSlipCommissionPercentage(value)
        } else {
            return validateBankSlipCommissionFixedValue(value, accountOwnerId)
        }
    }

    private CustomerCommissionConfig validateBankSlipCommissionPercentage(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message
        if (value > CustomerCommissionConfig.BANKSLIP_COMMISSION_MAX_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.BANKSLIP_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.BANKSLIP_COMMISSION_MIN_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.BANKSLIP_COMMISSION_MIN_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateBankSlipCommissionFixedValue(BigDecimal value, Long accountOwnerId) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message
        if (value > CustomerCommissionConfig.BANKSLIP_COMMISSION_MAX_FIXED_FEE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.BANKSLIP_COMMISSION_MAX_FIXED_FEE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.BANKSLIP_COMMISSION_MIN_FIXED_FEE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.BANKSLIP_COMMISSION_MIN_FIXED_FEE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa precisa ser superior a ${message}") as CustomerCommissionConfig
        }
        return validatedDomain
    }

    private CustomerCommissionConfig validateCreditCardCommissionConfig(BigDecimal value, BigDecimal valueUpToSix, BigDecimal valueUpToTwelve) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        Boolean anyEmptyOrNullValue = Utils.isEmptyOrNull(value) || Utils.isEmptyOrNull(valueUpToSix) || Utils.isEmptyOrNull(valueUpToTwelve)
        if (anyEmptyOrNullValue) {
            DomainUtils.addError(validatedDomain, "É necessário informar o valor do comissionamento para todas as opções")
            return validatedDomain
        }

        String message

        Boolean anyValueAboveMax = value > CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MAX_PERCENTAGE || valueUpToSix > CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MAX_PERCENTAGE || valueUpToTwelve > CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MAX_PERCENTAGE
        if (anyValueAboveMax) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        Boolean anyValueBelowMin = value < CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MIN_PERCENTAGE || valueUpToSix < CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MIN_PERCENTAGE || valueUpToTwelve < CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MIN_PERCENTAGE
        if (anyValueBelowMin) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.CREDIT_CARD_COMMISSION_MIN_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateDailyReceivableAnticipationCommissionConfig(BigDecimal percentage, String fieldName) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String commissionDescription = Utils.getMessageProperty("customerCommissionConfig.${fieldName}.label")

        if (Utils.isEmptyOrNull(percentage)) {
            return DomainUtils.addError(validatedDomain, "É necessário informar o valor da ${commissionDescription}") as CustomerCommissionConfig
        }

        String message

        if (percentage > CustomerCommissionConfig.RECEIVABLE_ANTICIPATION_COMMISSION_MAX_DAILY_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.RECEIVABLE_ANTICIPATION_COMMISSION_MAX_DAILY_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual da ${commissionDescription} precisa ser igual ou inferior a ${message}") as CustomerCommissionConfig
        }

        if (percentage < CustomerCommissionConfig.RECEIVABLE_ANTICIPATION_COMMISSION_MIN_DAILY_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.RECEIVABLE_ANTICIPATION_COMMISSION_MIN_DAILY_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual de ${commissionDescription} precisa ser igual ou superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validatePaymentDunningFeeConfig(CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateCommissionConfig(commissionType, value)
        if (validatedDomain.hasErrors()) return validatedDomain

        if (commissionType.isPercentage()) {
            return validatePaymentDunningFeeCommissionPercentage(value)
        } else {
            return validatePaymentDunningFeeCommissionFixedValue(value)
        }
    }

    private CustomerCommissionConfig validatePaymentDunningFeeCommissionPercentage(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message

        if (value > CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MAX_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MIN_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MIN_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validatePaymentDunningFeeCommissionFixedValue(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message

        if (value > CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MAX_FIXED_VALUE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MAX_FIXED_VALUE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MIN_FIXED_VALUE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.PAYMENT_DUNNING_COMMISSION_MAX_FIXED_VALUE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateInvoiceCommissionConfig(CustomerCommissionConfigType commissionType, BigDecimal value) {
        CustomerCommissionConfig validatedDomain = validateCommissionConfig(commissionType, value)
        if (validatedDomain.hasErrors()) return validatedDomain

        if (commissionType.isPercentage()) {
            return validateInvoiceFeePercentage(value)
        } else {
            return validateInvoiceFeeFixedValue(value)
        }
    }

    private CustomerCommissionConfig validateInvoiceFeePercentage(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message
        if (value > CustomerCommissionConfig.INVOICE_COMMISSION_MAX_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.INVOICE_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.INVOICE_COMMISSION_MIN_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.INVOICE_COMMISSION_MIN_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateInvoiceFeeFixedValue(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        String message
        if (value > CustomerCommissionConfig.INVOICE_COMMISSION_MAX_FIXED_VALUE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.INVOICE_COMMISSION_MAX_FIXED_VALUE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.INVOICE_COMMISSION_MIN_FIXED_VALUE) {
            message = FormUtils.formatCurrencyWithMonetarySymbol(CustomerCommissionConfig.INVOICE_COMMISSION_MIN_FIXED_VALUE)
            return DomainUtils.addError(validatedDomain, "O valor da taxa fixa deve ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }

    private CustomerCommissionConfig validateDebitCardCommissionConfig(BigDecimal value) {
        CustomerCommissionConfig validatedDomain = new CustomerCommissionConfig()

        if (Utils.isEmptyOrNull(value)) {
            DomainUtils.addError(validatedDomain, "É necessário informar o valor do comissionamento")
            return validatedDomain
        }

        String message
        if (value > CustomerCommissionConfig.DEBIT_CARD_COMMISSION_MAX_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.DEBIT_CARD_COMMISSION_MAX_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser inferior a ${message}") as CustomerCommissionConfig
        }

        if (value < CustomerCommissionConfig.DEBIT_CARD_COMMISSION_MIN_PERCENTAGE) {
            message = FormUtils.formatWithPercentageSymbol(CustomerCommissionConfig.DEBIT_CARD_COMMISSION_MIN_PERCENTAGE)
            return DomainUtils.addError(validatedDomain, "O valor percentual precisa ser superior a ${message}") as CustomerCommissionConfig
        }

        return validatedDomain
    }
}
