package com.asaas.service.creditcard

import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.creditcard.CreditCardFeeConfigHistory
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardFeeConfigService {

    public CreditCardFeeConfig saveForNewCustomer(Customer customer) {
        return saveForNewCustomer(customer, false)
    }

    public CreditCardFeeConfig saveForNewCustomer(Customer customer, Boolean hasEntryPromotionVariantC) {
        if (customer.accountOwner) {
            Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.accountOwner.id]).get().asBoolean()

            if (hasCustomerDealInfo) {
                return save(customer, buildFeeConfigWithoutDiscount())
            } else {
                return replicateAccountOwnerConfig(customer)
            }
        }

        SalesPartner salesPartner = SalesPartnerCustomer.query([customer: customer, column: 'salesPartner']).get()
        if (salesPartner) return save(customer, buildSalesPartnerFeeConfig(salesPartner))

        if (hasEntryPromotionVariantC) return save(customer, buildFeeConfigWithoutDiscount())

        return save(customer, buildDefaultCreditCardFeeConfig())
    }

    public Map buildFeeConfigWithoutDiscount() {
        Map feeConfig = [:]
        feeConfig.fixedFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FIXED_FEE
        feeConfig.upfrontFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FEE
        feeConfig.upToSixInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE
        feeConfig.upToTwelveInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE
        feeConfig.debitCardFixedFee = CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FIXED_FEE
        feeConfig.debitCardFee = CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FEE
        return feeConfig
    }

    public Map buildSalesPartnerFeeConfig(SalesPartner salesPartner) {
        Map feeConfig = [:]

        feeConfig.fixedFee = salesPartner.creditCardFixedFee
        feeConfig.upfrontFee = salesPartner.upfrontFee
        feeConfig.upToSixInstallmentsFee = salesPartner.upToSixInstallmentsFee
        feeConfig.upToTwelveInstallmentsFee = salesPartner.upToTwelveInstallmentsFee
        feeConfig.discountUpfrontFee = salesPartner.discountUpfrontFee == salesPartner.upfrontFee ? null : salesPartner.discountUpfrontFee
        feeConfig.discountUpToSixInstallmentsFee = salesPartner.discountUpToSixInstallmentsFee == salesPartner.upToSixInstallmentsFee ? null : salesPartner.discountUpToSixInstallmentsFee
        feeConfig.discountUpToTwelveInstallmentsFee = salesPartner.discountUpToTwelveInstallmentsFee == salesPartner.upToTwelveInstallmentsFee ? null : salesPartner.discountUpToTwelveInstallmentsFee
        feeConfig.debitCardFixedFee = salesPartner.debitCardFixedFee
        feeConfig.debitCardFee = salesPartner.debitCardFee

        if ( feeConfig.discountUpfrontFee || feeConfig.discountUpToSixInstallmentsFee || feeConfig.discountUpToTwelveInstallmentsFee ) {
            feeConfig.discountExpiration = CustomDateUtils.addMonths(new Date().clearTime(), CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
        }

        return feeConfig
    }

    public CreditCardFeeConfig save(Customer customer, Map feeConfig) {
        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.query([customer: customer]).get()
        if (!creditCardFeeConfig) {
            creditCardFeeConfig = new CreditCardFeeConfig()
            creditCardFeeConfig.customer = customer
        }

        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (feeConfig.containsKey("fixedFee")) {
            creditCardFeeConfig.fixedFee = feeConfig.fixedFee
        }

        if (feeConfig.containsKey("upfrontFee")) {
            creditCardFeeConfig.upfrontFee = feeConfig.upfrontFee + (customerCommissionConfig?.creditCardPaymentFeePercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("upToSixInstallmentsFee")) {
            creditCardFeeConfig.upToSixInstallmentsFee = feeConfig.upToSixInstallmentsFee + (customerCommissionConfig?.creditCardPaymentFeeUpToSixPercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("upToTwelveInstallmentsFee")) {
            creditCardFeeConfig.upToTwelveInstallmentsFee = feeConfig.upToTwelveInstallmentsFee + (customerCommissionConfig?.creditCardPaymentFeeUpToTwelvePercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("discountUpfrontFee")) {
            creditCardFeeConfig.discountUpfrontFee = feeConfig.discountUpfrontFee
            if (creditCardFeeConfig.discountUpfrontFee != null) creditCardFeeConfig.discountUpfrontFee += (customerCommissionConfig?.creditCardPaymentFeePercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("discountUpToSixInstallmentsFee")) {
            creditCardFeeConfig.discountUpToSixInstallmentsFee = feeConfig.discountUpToSixInstallmentsFee
            if (creditCardFeeConfig.discountUpToSixInstallmentsFee != null) creditCardFeeConfig.discountUpToSixInstallmentsFee += (customerCommissionConfig?.creditCardPaymentFeeUpToSixPercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("discountUpToTwelveInstallmentsFee")) {
            creditCardFeeConfig.discountUpToTwelveInstallmentsFee = feeConfig.discountUpToTwelveInstallmentsFee
            if (creditCardFeeConfig.discountUpToTwelveInstallmentsFee != null) creditCardFeeConfig.discountUpToTwelveInstallmentsFee += (customerCommissionConfig?.creditCardPaymentFeeUpToTwelvePercentageWithOverprice ?: 0.0)
        }

        if (feeConfig.containsKey("debitCardFixedFee")) {
            creditCardFeeConfig.debitCardFixedFee = feeConfig.debitCardFixedFee
        }

        if (feeConfig.containsKey("debitCardFee")) {
            creditCardFeeConfig.debitCardFee = feeConfig.debitCardFee
        }

        if (feeConfig.containsKey("discountExpiration")) {
            creditCardFeeConfig.discountExpiration = feeConfig.discountExpiration
        }

        creditCardFeeConfig = expireDiscountIfDefaultValueIsLower(creditCardFeeConfig)
        creditCardFeeConfig.save(flush: true, failOnError: true)

        saveHistory(creditCardFeeConfig)

        return creditCardFeeConfig
    }

    public CreditCardFeeConfig replicateAccountOwnerConfig(Customer childAccount) {
        Customer accountOwner = childAccount?.accountOwner
        if (!accountOwner) throw new BusinessException(Utils.getMessageProperty("customer.dontHaveAccountOwner"))

        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.query([customerId: accountOwner.id]).get()
        if (!creditCardFeeConfig) return null

        return save(childAccount, [
            fixedFee: creditCardFeeConfig.fixedFee,
            upfrontFee: creditCardFeeConfig.upfrontFee,
            upToSixInstallmentsFee: creditCardFeeConfig.upToSixInstallmentsFee,
            upToTwelveInstallmentsFee: creditCardFeeConfig.upToTwelveInstallmentsFee,
            debitCardFixedFee: creditCardFeeConfig.debitCardFixedFee,
            debitCardFee: creditCardFeeConfig.debitCardFee,
            discountExpiration: creditCardFeeConfig.discountExpiration,
            discountUpfrontFee: creditCardFeeConfig.discountUpfrontFee,
            discountUpToSixInstallmentsFee: creditCardFeeConfig.discountUpToSixInstallmentsFee,
            discountUpToTwelveInstallmentsFee: creditCardFeeConfig.discountUpToTwelveInstallmentsFee
        ])
    }

    public CreditCardFeeConfig incrementDiscountExpiration(Customer customer) {
        CreditCardFeeConfig creditCardFeeConfig = CreditCardFeeConfig.query([customerId: customer.id]).get()
        if (!creditCardFeeConfig) {
            return save(customer, buildDefaultCreditCardFeeConfig())
        }

        Date currentDiscountExpiration = creditCardFeeConfig.hasValidDiscount() ? creditCardFeeConfig.discountExpiration : new Date().clearTime()

        creditCardFeeConfig.discountExpiration = CustomDateUtils.addMonths(currentDiscountExpiration, CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)

        if (!creditCardFeeConfig.discountUpfrontFee) {
            creditCardFeeConfig.discountUpfrontFee = creditCardFeeConfig.calculateDiscountUpfrontFee()
        }

        if (!creditCardFeeConfig.discountUpToSixInstallmentsFee) {
            creditCardFeeConfig.discountUpToSixInstallmentsFee = creditCardFeeConfig.calculateDiscountUpToSixInstallmentsFee()
        }

        if (!creditCardFeeConfig.discountUpToTwelveInstallmentsFee) {
            creditCardFeeConfig.discountUpToTwelveInstallmentsFee = creditCardFeeConfig.calculateDiscountUpToTwelveInstallmentsFee()
        }

        creditCardFeeConfig.save(failOnError: true)

        return creditCardFeeConfig
    }

    public Map buildDefaultCreditCardFeeConfig() {
        Map feeConfig = [:]

        feeConfig.fixedFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FIXED_FEE
        feeConfig.upfrontFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_FEE
        feeConfig.upToSixInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE
        feeConfig.upToTwelveInstallmentsFee = CreditCardFeeConfig.DEFAULT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE
        feeConfig.discountUpfrontFee = CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_FEE
        feeConfig.discountUpToSixInstallmentsFee = CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_UP_TO_SIX_INSTALLMENTS_FEE
        feeConfig.discountUpToTwelveInstallmentsFee = CreditCardFeeConfig.DISCOUNT_CREDIT_CARD_UP_TO_TWELVE_INSTALLMENTS_FEE
        feeConfig.debitCardFixedFee = CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FIXED_FEE
        feeConfig.debitCardFee = CreditCardFeeConfig.DEFAULT_DEBIT_CARD_FEE
        feeConfig.discountExpiration = CustomDateUtils.addMonths(new Date().clearTime(), CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)

        return feeConfig
    }

    private CreditCardFeeConfig expireDiscountIfDefaultValueIsLower(CreditCardFeeConfig creditCardFeeConfig) {
        if (!creditCardFeeConfig.hasValidDiscount()) return creditCardFeeConfig

        if (creditCardFeeConfig.upfrontFee < creditCardFeeConfig.discountUpfrontFee ||
            creditCardFeeConfig.upToSixInstallmentsFee < creditCardFeeConfig.discountUpToSixInstallmentsFee ||
            creditCardFeeConfig.upToTwelveInstallmentsFee < creditCardFeeConfig.discountUpToTwelveInstallmentsFee) {
            creditCardFeeConfig.discountExpiration = CustomDateUtils.getYesterday()
        }

        return creditCardFeeConfig
    }

    private void saveHistory(CreditCardFeeConfig feeConfig) {
        CreditCardFeeConfigHistory history = new CreditCardFeeConfigHistory()

        history.creditCardFeeConfig = feeConfig
        history.properties["fixedFee", "upfrontFee", "upToSixInstallmentsFee", "upToTwelveInstallmentsFee",
            "discountUpfrontFee", "discountUpToSixInstallmentsFee", "discountUpToTwelveInstallmentsFee",
            "debitCardFixedFee", "debitCardFee", "discountExpiration"] = feeConfig

        history.save(failOnError: true)
    }
}
