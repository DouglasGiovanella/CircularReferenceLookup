package com.asaas.service.creditcard

import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class CreditCardFeeConfigAdminService {

    def feeNegotiationService

    public CreditCardFeeConfig validateCreditCardFee(Long customerId, CreditCardFeeConfig oldCreditCardFeeConfig, Map newFeeConfigInfo) {
        CreditCardFeeConfig validatedCreditCardFeeConfig = new CreditCardFeeConfig()
        if (newFeeConfigInfo.fixedFee == null) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Informe um valor válido.")
        }

        if (newFeeConfigInfo.discountExpirationInMonths != null && newFeeConfigInfo.discountExpirationInMonths < 1) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "É necessário informar um número de meses igual ou superior a 1.")
        }

        if ([newFeeConfigInfo.upfrontFee, newFeeConfigInfo.upToSixInstallmentsFee, newFeeConfigInfo.upToTwelveInstallmentsFee].any { !it }) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Informe um valor válido.")
        }

        if ([newFeeConfigInfo.discountUpfrontFee, newFeeConfigInfo.discountUpToSixInstallmentsFee, newFeeConfigInfo.discountUpToTwelveInstallmentsFee].any { !it }) {
            if (newFeeConfigInfo.discountExpiration || newFeeConfigInfo.discountExpirationInMonths) {
                DomainUtils.addError(validatedCreditCardFeeConfig, "Verifique todos os valores de desconto informados.")
            }
        }

        if ([newFeeConfigInfo.discountUpfrontFee, newFeeConfigInfo.discountUpToSixInstallmentsFee, newFeeConfigInfo.discountUpToTwelveInstallmentsFee].any { it }) {
            if (!newFeeConfigInfo.discountExpiration && !newFeeConfigInfo.discountExpirationInMonths) {
                DomainUtils.addError(validatedCreditCardFeeConfig, "Verifique a data de validade dos descontos informados.")
            }

            if (newFeeConfigInfo.discountExpiration && newFeeConfigInfo.discountExpirationInMonths) {
                DomainUtils.addError(validatedCreditCardFeeConfig, "Não é possível adicionar a data de expiração do desconto e o número de meses para expiração do desconto ao mesmo tempo.")
            }
        }

        if (newFeeConfigInfo.discountUpfrontFee > newFeeConfigInfo.upfrontFee) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Taxa promocional de cobranças à vista não pode ser superior ao valor original.")
        }

        if (newFeeConfigInfo.discountUpToSixInstallmentsFee > newFeeConfigInfo.upToSixInstallmentsFee) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Taxa promocional de parcelamento entre 2 e 6 não pode ser superior ao valor original.")
        }

        if (newFeeConfigInfo.discountUpToTwelveInstallmentsFee > newFeeConfigInfo.upToTwelveInstallmentsFee) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Taxa promocional de parcelamento entre 7 e 12 não pode ser superior ao valor original.")
        }

        Boolean allowedFeeBelowDefaultLimit = feeNegotiationService.isAllowedChangeFeeBelowDefaultLimit(customerId)

        BigDecimal minimumCreditCardFeeValue = allowedFeeBelowDefaultLimit ? 0 : CreditCardFeeConfig.MINIMUM_CREDIT_CARD_FEE
        if ([newFeeConfigInfo.upfrontFee, newFeeConfigInfo.upToSixInstallmentsFee, newFeeConfigInfo.upToTwelveInstallmentsFee].any { it && it < minimumCreditCardFeeValue }) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Os valores informados para taxa não podem ser menores que ${minimumCreditCardFeeValue}%.")
        }

        BigDecimal minimumCreditCardFeeDiscountValue = allowedFeeBelowDefaultLimit ? 0 : CreditCardFeeConfig.MINIMUM_CREDIT_CARD_FEE_DISCOUNT
        if ([newFeeConfigInfo.discountUpfrontFee, newFeeConfigInfo.discountUpToSixInstallmentsFee, newFeeConfigInfo.discountUpToTwelveInstallmentsFee].any { it && it < minimumCreditCardFeeDiscountValue }) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Os valores informados para taxa promocional não podem ser menores que ${minimumCreditCardFeeDiscountValue}%.")
        }

        Date discountExpirationLimit = CustomDateUtils.addMonths(new Date().clearTime(), CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
        if (newFeeConfigInfo.discountExpiration > discountExpirationLimit) {
            DomainUtils.addError(validatedCreditCardFeeConfig, "Não é possível conceder mais que ${CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS} meses para a data de validade da taxa promocional do cliente.")
        }

        return validatedCreditCardFeeConfig
    }

    public BigDecimal findCreditCardCommissionPercentageFromCreditCardFeeConfigColumnName(Map creditCardPaymentCommissionConfig, String columnName) {
        if (columnName == "upfrontFee" || columnName == "discountUpfrontFee") {
            return creditCardPaymentCommissionConfig?.creditCardPaymentFeePercentageWithOverprice ?: 0.0
        } else if (columnName == "upToSixInstallmentsFee" || columnName == "discountUpToSixInstallmentsFee") {
            return creditCardPaymentCommissionConfig?.creditCardPaymentFeeUpToSixPercentageWithOverprice ?: 0.0
        }
        return creditCardPaymentCommissionConfig?.creditCardPaymentFeeUpToTwelvePercentageWithOverprice ?: 0.0
    }

    public Map findCreditCardPaymentCommissionConfig(Long accountOwnerId) {
        return CustomerCommissionConfig.query([columnList: ["creditCardPaymentFeePercentageWithOverprice", "creditCardPaymentFeeUpToSixPercentageWithOverprice", "creditCardPaymentFeeUpToTwelvePercentageWithOverprice"], customerId: accountOwnerId, disableSort: true]).get()
    }

}
