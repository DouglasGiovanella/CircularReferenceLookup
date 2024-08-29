package com.asaas.service.payment

import com.asaas.bankslip.BankSlipFeeType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.Referral
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.BankSlipFeeHistory
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class BankSlipFeeService {

    def customerInteractionService
    def customerParameterService
    def feeNegotiationService

    public BankSlipFee saveForNewCustomer(Customer customer) {
        return saveForNewCustomer(customer, false)
    }

    public BankSlipFee saveForNewCustomer(Customer customer, Boolean hasEntryPromotionVariantC) {
        if (customer.accountOwner) {
            Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.accountOwner.id]).get().asBoolean()

            if (hasCustomerDealInfo) {
                return save(customer, BankSlipFee.DEFAULT_BANK_SLIP_FEE, null)
            } else {
                BankSlipFee accountOwnerBankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer.accountOwner)
                return save(customer, accountOwnerBankSlipFee.defaultValue, null)
            }
        }

        SalesPartner salesPartner = SalesPartnerCustomer.query([customer: customer, column: 'salesPartner']).get()
        if (salesPartner) return save(customer, salesPartner.bankSlipFee, salesPartner.discountBankSlipFee)

        if (hasEntryPromotionVariantC) return save(customer, BankSlipFee.DEFAULT_BANK_SLIP_FEE, null)

        return save(customer, BankSlipFee.DEFAULT_BANK_SLIP_FEE, BankSlipFee.DISCOUNT_BANK_SLIP_FEE)
    }

    public BankSlipFee setupChildAccount(Customer customer, Map feeConfig) {
        if (!customer.accountOwner) throw new BusinessException(Utils.getMessageProperty("customer.dontHaveAccountOwner"))

        BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer)

        if (bankSlipFee) saveHistory(bankSlipFee, null)

        if (feeConfig.containsKey("defaultValue")) {
            bankSlipFee.defaultValue = buildDefaultValueWithCommission(customer, feeConfig.defaultValue)
        }

        if (feeConfig.containsKey("discountValue")) {
            bankSlipFee.discountValue = buildDiscountValueWithCommission(customer, feeConfig.discountValue)
        }

        if (feeConfig.containsKey("discountExpiration")) {
            bankSlipFee.discountExpiration = feeConfig.discountExpiration
        }

        bankSlipFee.save(failOnError: true, flush: true)

        return bankSlipFee
    }

    public BankSlipFee update(Customer customer, BigDecimal defaultValue, BigDecimal discountValue, Date discountExpiration, Boolean fromAccountOwner) {
        BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer) ?: saveForNewCustomer(customer)

        BusinessValidation businessValidation = validateUpdate(customer.id, defaultValue, discountValue, discountExpiration, bankSlipFee.discountExpiration)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(bankSlipFee, businessValidation.getFirstErrorMessage())
            return bankSlipFee
        }

        if (defaultValue == CustomerFee.ASAAS_COLLABORATOR_FEE) {
            discountValue = CustomerFee.ASAAS_COLLABORATOR_FEE
            discountExpiration = null
        }

        saveHistory(bankSlipFee, null)

        bankSlipFee.defaultValue = defaultValue
        bankSlipFee.discountValue = discountValue
        bankSlipFee.discountExpiration = discountExpiration
        if (!bankSlipFee.type) bankSlipFee.type = BankSlipFeeType.FIXED

        if (bankSlipFee.type.isFixed()) {
            bankSlipFee.defaultValue = buildDefaultValueWithCommission(customer, bankSlipFee.defaultValue)
            bankSlipFee.discountValue = buildDiscountValueWithCommission(customer, bankSlipFee.discountValue)
        }

        bankSlipFee.save(failOnError: true)

        Map valuesChanged = [
                defaultValue: bankSlipFee.defaultValue,
                discountValue: bankSlipFee.discountValue,
                discountExpiration: bankSlipFee.discountExpiration
        ]

        onBankSlipFeeChange(bankSlipFee, valuesChanged, fromAccountOwner)

        customerParameterService.save(customer, CustomerParameterName.CANNOT_USE_REFERRAL, !discountValue)

        return bankSlipFee
    }

    public BusinessValidation validateUpdate(Long customerId, BigDecimal defaultValue, BigDecimal discountValue, Date newDiscountExpiration, Date oldDiscountExpiration) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (defaultValue == CustomerFee.ASAAS_COLLABORATOR_FEE) return businessValidation

        if (!defaultValue) {
            businessValidation.addError("bankSlipFee.defaultValue.invalid")
            return businessValidation
        }

        Boolean allowedFeeBelowDefaultLimit = feeNegotiationService.isAllowedChangeFeeBelowDefaultLimit(customerId)

        BigDecimal minimumDiscountValue = allowedFeeBelowDefaultLimit ? 0 : BankSlipFee.MINIMUM_DISCOUNT_VALUE
        if (defaultValue < minimumDiscountValue) {
            businessValidation.addError("bankSlipFee.defaultValue.belowMinimum", [FormUtils.formatCurrencyWithMonetarySymbol(minimumDiscountValue)])
            return businessValidation
        }

        if (discountValue) {
            if (discountValue >= defaultValue) {
                businessValidation.addError("bankSlipFee.discountValue.aboveOrEqualDefaultValue")
                return businessValidation
            }

            if (discountValue < minimumDiscountValue) {
                businessValidation.addError("bankSlipFee.discountValue.belowMinimum", [FormUtils.formatCurrencyWithMonetarySymbol(minimumDiscountValue)])
                return businessValidation
            }

            if (!newDiscountExpiration) {
                businessValidation.addError("bankSlipFee.discountExpiration.empty")
                return businessValidation
            }
        }

        if (newDiscountExpiration) {
            if (newDiscountExpiration < oldDiscountExpiration) {
                businessValidation.addError("bankSlipFee.discountExpiration.reduceDiscountExpiration")
                return businessValidation
            }

            Date baseDiscountExpiration = BankSlipFee.discountIsExpired(oldDiscountExpiration) ? new Date().clearTime() : oldDiscountExpiration
            Date discountExpirationIncremented = CustomDateUtils.addMonths(baseDiscountExpiration, BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
            if (newDiscountExpiration > discountExpirationIncremented) {
                businessValidation.addError("bankSlipFee.discountExpiration.invalidDate", [BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS])
                return businessValidation
            }

            if (!discountValue) {
                businessValidation.addError("bankSlipFee.discountValue.invalid")
                return businessValidation
            }
        }

        return businessValidation
    }

	public BankSlipFee incrementDiscountExpiration(Customer customer, Referral referral) {
		BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer)
		if (!bankSlipFee) {
			return
		}
		saveHistory(bankSlipFee, referral)

		Date baseDiscountExpiration = bankSlipFee.discountIsExpired() ? new Date().clearTime() : bankSlipFee.discountExpiration

		bankSlipFee.discountExpiration = CustomDateUtils.addMonths(baseDiscountExpiration, BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
        if (!bankSlipFee.discountValue) {
            bankSlipFee.discountValue = BankSlipFee.calculateDiscountValue(bankSlipFee.defaultValue)
        }
		bankSlipFee.save(failOnError: true)

		return bankSlipFee
	}

    private BankSlipFee save(Customer customer, BigDecimal defaultValue, BigDecimal discountValue) {
        BankSlipFee bankSlipFee = new BankSlipFee()
        bankSlipFee.customer = customer
        bankSlipFee.defaultValue = buildDefaultValueWithCommission(customer, defaultValue)
        bankSlipFee.discountValue = buildDiscountValueWithCommission(customer, discountValue)
        bankSlipFee.discountExpiration = discountValue ? CustomDateUtils.addMonths(new Date().clearTime(), BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS) : null
        bankSlipFee.type = BankSlipFeeType.FIXED
        bankSlipFee.save(failOnError: true, flush: true)

        return bankSlipFee
    }

    private BigDecimal buildDefaultValueWithCommission(Customer customer, BigDecimal defaultValue) {
        CustomerCommissionConfig accountOwnerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)

        return defaultValue + (accountOwnerCommissionConfig?.bankSlipFeeFixedValueWithOverprice ?: 0.0)
    }

    private BigDecimal buildDiscountValueWithCommission(Customer customer, BigDecimal discountValue) {
        CustomerCommissionConfig accountOwnerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (discountValue && accountOwnerCommissionConfig?.bankSlipFeeFixedValueWithOverprice) return discountValue + accountOwnerCommissionConfig.bankSlipFeeFixedValueWithOverprice

        return discountValue
    }

	private BankSlipFeeHistory saveHistory(BankSlipFee bankSlipFee, Referral referral) {
		BankSlipFeeHistory bankSlipFeeHistory = new BankSlipFeeHistory()

		bankSlipFeeHistory.bankSlipFee = bankSlipFee
		bankSlipFeeHistory.discountValue = bankSlipFee.discountValue
		bankSlipFeeHistory.defaultValue = bankSlipFee.defaultValue
		bankSlipFeeHistory.discountExpiration = bankSlipFee.discountExpiration
		bankSlipFeeHistory.type = bankSlipFee.type
		bankSlipFeeHistory.referral = referral

		bankSlipFeeHistory.save(failOnError: true)
		return bankSlipFeeHistory
	}

    private void onBankSlipFeeChange(BankSlipFee bankSlipFee, Map valuesChanged, Boolean fromAccountOwner) {
        customerInteractionService.saveUpdateBankSlipFee(bankSlipFee.customer, valuesChanged, fromAccountOwner)
    }
}
