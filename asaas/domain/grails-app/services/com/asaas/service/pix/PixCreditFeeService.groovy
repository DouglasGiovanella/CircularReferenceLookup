package com.asaas.service.pix

import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.salespartner.SalesPartner
import com.asaas.domain.salespartner.SalesPartnerCustomer
import com.asaas.exception.BusinessException
import com.asaas.feeConfiguration.adapter.PixCreditFeeAdapter
import com.asaas.pix.PixCreditFeeType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class PixCreditFeeService {

    def customerInteractionService
    def pixCreditFeeHistoryService
    def feeNegotiationService

    public PixCreditFee saveForNewCustomer(Customer customer, Boolean hasEntryPromotionVariantC) {
        if (customer.accountOwner) {
            Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: customer.accountOwner.id]).get().asBoolean()

            if (hasCustomerDealInfo) {
                return saveFixedFee(customer, PixCreditFee.DEFAULT_PIX_FEE, null, null, false, true)
            } else {
                PixCreditFee accountOwnerPixCreditFee = PixCreditFee.query([customer: customer.accountOwner]).get()

                if (accountOwnerPixCreditFee.type.isFixed()) {
                    return saveFixedFee(customer, accountOwnerPixCreditFee.fixedFee, null, null, true, true)
                } else if (accountOwnerPixCreditFee.type.isPercentage()) {
                    return savePercentageFee(customer, accountOwnerPixCreditFee.percentageFee, accountOwnerPixCreditFee.minimumFee, accountOwnerPixCreditFee.maximumFee, true, true)
                }
            }
        }

        SalesPartner salesPartner = SalesPartnerCustomer.query([customer: customer, column: 'salesPartner']).get()
        if (salesPartner && salesPartner.pixFeeType) return buildSalesPartnerFeeConfig(customer, salesPartner)

        if (hasEntryPromotionVariantC) return saveFixedFee(customer, PixCreditFee.DEFAULT_PIX_FEE, null, null, false, true)

        Date discountExpiration = CustomDateUtils.addMonths(customer.dateCreated.clone().clearTime(), PixCreditFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
        return saveFixedFee(customer, PixCreditFee.DEFAULT_PIX_FEE, PixCreditFee.DISCOUNT_PIX_FEE, discountExpiration, false, true)
    }

    public PixCreditFee savePercentageFee(Customer customer, BigDecimal percentageFee, BigDecimal minimumFee, BigDecimal maximumFee, Boolean fromAccountOwner, Boolean failOnError) {
        PixCreditFee validatedPixCreditFee = validateSavePercentageFee(percentageFee, minimumFee, maximumFee)

        if (validatedPixCreditFee.hasErrors()) {
            if (failOnError) throw new ValidationException(buildSaveValidationMessage(customer, validatedPixCreditFee), validatedPixCreditFee.errors)
            return validatedPixCreditFee
        }

        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

        if (!pixCreditFee) {
            pixCreditFee = new PixCreditFee()
            pixCreditFee.customer = customer
        } else {
            pixCreditFeeHistoryService.save(pixCreditFee)
        }

        pixCreditFee.type = PixCreditFeeType.PERCENTAGE
        pixCreditFee.percentageFee = percentageFee
        pixCreditFee.minimumFee = minimumFee
        pixCreditFee.maximumFee = maximumFee
        pixCreditFee.fixedFee = null
        pixCreditFee.fixedFeeWithDiscount = null
        pixCreditFee.discountExpiration = null

        CustomerCommissionConfig partnerCommissionPixPaymentFeeConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (partnerCommissionPixPaymentFeeConfig?.pixFeePercentageWithOverprice) pixCreditFee.percentageFee += partnerCommissionPixPaymentFeeConfig.pixFeePercentageWithOverprice
        if (partnerCommissionPixPaymentFeeConfig?.pixMinimumFee) pixCreditFee.minimumFee += partnerCommissionPixPaymentFeeConfig.pixMinimumFee
        if (partnerCommissionPixPaymentFeeConfig?.pixMaximumFee) pixCreditFee.maximumFee += partnerCommissionPixPaymentFeeConfig.pixMaximumFee

        pixCreditFee = pixCreditFee.save(failOnError: true)

        if (pixCreditFee.hasErrors()) {
            if (failOnError) throw new ValidationException(buildSaveValidationMessage(customer, pixCreditFee), pixCreditFee.errors)
            return pixCreditFee
        }

        onUpdatePixCreditPercentageFee(pixCreditFee, fromAccountOwner)

        return pixCreditFee
    }

    public PixCreditFee saveFixedFee(Customer customer, BigDecimal fixedFee, BigDecimal fixedFeeWithDiscount, Date discountExpiration, Boolean fromAccountOwner, Boolean failOnError) {
        if (fixedFee <= fixedFeeWithDiscount) {
            fixedFeeWithDiscount = null
            discountExpiration = null
        }

        PixCreditFee validatedPixCreditFee = validateSaveFixedFee(customer, fixedFee, fixedFeeWithDiscount, discountExpiration)

        if (validatedPixCreditFee.hasErrors()) {
            if (failOnError) throw new ValidationException(buildSaveValidationMessage(customer, validatedPixCreditFee), validatedPixCreditFee.errors)
            return validatedPixCreditFee
        }

        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

        if (!pixCreditFee) {
            pixCreditFee = new PixCreditFee()
            pixCreditFee.customer = customer
        } else {
            pixCreditFeeHistoryService.save(pixCreditFee)
        }

        pixCreditFee.type = PixCreditFeeType.FIXED
        pixCreditFee.fixedFee = fixedFee
        pixCreditFee.fixedFeeWithDiscount = fixedFeeWithDiscount
        pixCreditFee.discountExpiration = discountExpiration
        pixCreditFee.percentageFee = null
        pixCreditFee.minimumFee = null
        pixCreditFee.maximumFee = null

        CustomerCommissionConfig accountOwnerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)
        if (accountOwnerCommissionConfig?.pixFeeFixedValueWithOverprice) {
            pixCreditFee.fixedFee += accountOwnerCommissionConfig.pixFeeFixedValueWithOverprice
            if (pixCreditFee?.fixedFeeWithDiscount) pixCreditFee.fixedFeeWithDiscount += accountOwnerCommissionConfig.pixFeeFixedValueWithOverprice
        }

        pixCreditFee = pixCreditFee.save(failOnError: true)

        if (pixCreditFee.hasErrors()) {
            if (failOnError) throw new ValidationException(buildSaveValidationMessage(customer, pixCreditFee), pixCreditFee.errors)
            return pixCreditFee
        }

        onUpdatePixCreditFixedFee(pixCreditFee, fromAccountOwner)

        return pixCreditFee
    }

    public PixCreditFee validateSavePercentageFee(BigDecimal percentageFee, BigDecimal minimumFee, BigDecimal maximumFee) {
        PixCreditFee validatedPixCreditFee = new PixCreditFee()

        if (!percentageFee) return DomainUtils.addError(validatedPixCreditFee, "Taxa percentual de Pix precisa ser informada.")
        if (!minimumFee) return DomainUtils.addError(validatedPixCreditFee, "Para taxa percentual de Pix, a taxa mínima precisa ser informada.")
        if (!maximumFee) return DomainUtils.addError(validatedPixCreditFee, "Para taxa percentual de Pix, a taxa máxima precisa ser informada.")
        if (minimumFee > maximumFee) return DomainUtils.addError(validatedPixCreditFee, "A taxa mínima não deve ser maior que a taxa máxima informada.")

        return validatedPixCreditFee
    }

    public PixCreditFee validateSaveFixedFee(Customer customer, BigDecimal fixedFee, BigDecimal fixedFeeWithDiscount, Date discountExpiration) {
        PixCreditFee validatedPixCreditFee = new PixCreditFee()

        if (fixedFee == null) return DomainUtils.addError(validatedPixCreditFee, "Taxa fixa de Pix precisa ser informada.")

        if (fixedFee == CustomerFee.ASAAS_COLLABORATOR_FEE) return validatedPixCreditFee

        Boolean allowedFeeBelowDefaultLimit = feeNegotiationService.isAllowedChangeFeeBelowDefaultLimit(customer.id)

        BigDecimal minimumDiscountValue = allowedFeeBelowDefaultLimit ? 0 : PixCreditFee.MINIMUM_DISCOUNT_VALUE

        if (fixedFeeWithDiscount) {
            if (fixedFeeWithDiscount < minimumDiscountValue) return DomainUtils.addError(validatedPixCreditFee, "A taxa promocional não pode ser menor que ${FormUtils.formatCurrencyWithMonetarySymbol(minimumDiscountValue)}.")
            if (!discountExpiration) return DomainUtils.addError(validatedPixCreditFee, "É necessário informar uma data de validade para a taxa promocional do cliente.")
        }

        if (discountExpiration) {
            PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

            if (discountExpiration < pixCreditFee?.discountExpiration) return DomainUtils.addError(validatedPixCreditFee, "Não é possível reduzir a data de validade para a taxa promocional do cliente.")

            Date baseDiscountExpiration = !pixCreditFee || pixCreditFee?.isDiscountExpired() ? new Date().clearTime() : pixCreditFee.discountExpiration
            Date discountExpirationIncremented = CustomDateUtils.addMonths(baseDiscountExpiration, PixCreditFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)

            if (discountExpiration > discountExpirationIncremented) return DomainUtils.addError(validatedPixCreditFee, "Não é possível conceder mais que ${PixCreditFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS} meses para a data de validade da taxa promocional do cliente.")
            if (!fixedFeeWithDiscount) return DomainUtils.addError(validatedPixCreditFee, "É necessário informar uma taxa promocional para a data de validade do cliente.")
        }

        return validatedPixCreditFee
    }

    public PixCreditFee setupChildAccount(Customer customer, Map feeConfig) {
        if (!customer.accountOwner) throw new BusinessException(Utils.getMessageProperty("customer.dontHaveAccountOwner"))

        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

        if (pixCreditFee) pixCreditFeeHistoryService.save(pixCreditFee)

        if (feeConfig.containsKey("type")) {
            pixCreditFee.type = feeConfig.type

            if (pixCreditFee.type.isFixed()) {
                pixCreditFee.percentageFee = null
                pixCreditFee.minimumFee = null
                pixCreditFee.maximumFee = null
            } else if (pixCreditFee.type.isPercentage()) {
                pixCreditFee.fixedFee = null
                pixCreditFee.fixedFeeWithDiscount = null
                pixCreditFee.discountExpiration = null
            }
        }

        CustomerCommissionConfig accountOwnerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)

        if (feeConfig.containsKey("fixedFee")) {
            pixCreditFee.fixedFee = feeConfig.fixedFee
            if (pixCreditFee.fixedFee != null && accountOwnerCommissionConfig?.pixFeeFixedValueWithOverprice != null) {
                pixCreditFee.fixedFee += accountOwnerCommissionConfig.pixFeeFixedValueWithOverprice
            }
        }

        if (feeConfig.containsKey("fixedFeeWithDiscount")) {
            pixCreditFee.fixedFeeWithDiscount = feeConfig.fixedFeeWithDiscount
            if (pixCreditFee.fixedFeeWithDiscount != null && accountOwnerCommissionConfig?.pixFeeFixedValueWithOverprice != null) {
                pixCreditFee.fixedFeeWithDiscount += accountOwnerCommissionConfig?.pixFeeFixedValueWithOverprice
            }
        }

        if (feeConfig.containsKey("discountExpiration")) {
            pixCreditFee.discountExpiration = feeConfig.discountExpiration
        }

        if (feeConfig.containsKey("percentageFee")) {
            pixCreditFee.percentageFee = feeConfig.percentageFee
            if (pixCreditFee.percentageFee != null && accountOwnerCommissionConfig?.pixFeePercentageWithOverprice != null) {
                pixCreditFee.percentageFee += accountOwnerCommissionConfig.pixFeePercentageWithOverprice
            }
        }

        if (feeConfig.containsKey("minimumFee")) {
            pixCreditFee.minimumFee = feeConfig.minimumFee

            if (pixCreditFee.minimumFee != null && accountOwnerCommissionConfig?.pixMinimumFee != null) {
                pixCreditFee.minimumFee += accountOwnerCommissionConfig.pixMinimumFee
            }
        }

        if (feeConfig.containsKey("maximumFee")) {
            pixCreditFee.maximumFee = feeConfig.maximumFee

            if (pixCreditFee.maximumFee != null && accountOwnerCommissionConfig?.pixMaximumFee != null) {
                pixCreditFee.maximumFee += accountOwnerCommissionConfig.pixMaximumFee
            }
        }

        pixCreditFee.save(failOnError: true, flush: true)

        return pixCreditFee
    }

    public PixCreditFeeAdapter calculatePixCreditFeeWithoutOverprice(Customer customer) {
        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer, readOnly: true]).get()
        if (!pixCreditFee) return null


        CustomerCommissionConfig customerCommissionConfig = CustomerCommissionConfig.getCommissionedCustomerConfig(customer)

        PixCreditFeeAdapter pixCreditFeeAdapter = new PixCreditFeeAdapter()
        if (pixCreditFee.fixedFee) {
            pixCreditFeeAdapter.fixedFee = pixCreditFee.fixedFee - (customerCommissionConfig?.pixFeeFixedValueWithOverprice ?: 0.0)
        }

        if (pixCreditFee.fixedFeeWithDiscount) {
            pixCreditFeeAdapter.fixedFeeWithDiscount = pixCreditFee.fixedFeeWithDiscount - (customerCommissionConfig?.pixFeeFixedValueWithOverprice ?: 0.0)
        }

        if (pixCreditFee.percentageFee) {
            pixCreditFeeAdapter.percentageFee = pixCreditFee.percentageFee - (customerCommissionConfig?.pixFeePercentageWithOverprice ?: 0.0)
        }

        if (pixCreditFee.minimumFee) {
            pixCreditFeeAdapter.minimumFee = pixCreditFee.minimumFee - (customerCommissionConfig?.pixMinimumFee ?: 0.0)
        }

        if (pixCreditFee.maximumFee) {
            pixCreditFeeAdapter.maximumFee = pixCreditFee.maximumFee - (customerCommissionConfig?.pixMinimumFee ?: 0.0)
        }

        pixCreditFeeAdapter.id = pixCreditFee.id
        pixCreditFeeAdapter.discountExpiration = pixCreditFee.discountExpiration
        pixCreditFeeAdapter.dateCreated = pixCreditFee.dateCreated
        pixCreditFeeAdapter.type = pixCreditFee.type

        return pixCreditFeeAdapter
    }

    private PixCreditFee buildSalesPartnerFeeConfig(Customer customer, SalesPartner salesPartner) {
        if (salesPartner.pixFeeType.isFixed()) {
            Date discountExpiration = CustomDateUtils.addMonths(customer.dateCreated.clone().clearTime(), PixCreditFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS)
            return saveFixedFee(customer, salesPartner.pixFixedFee, salesPartner.discountPixFixedFee, salesPartner.discountPixFixedFee ? discountExpiration : null, false, true)
        }

        return savePercentageFee(customer, salesPartner.pixPercentageFee, salesPartner.pixMinimumFee, salesPartner.pixMaximumFee, false, true)
    }

    private void onUpdatePixCreditFixedFee(PixCreditFee pixCreditFee, Boolean fromAccountOwner) {
        Map valuesChanged = [fixedFee: pixCreditFee.fixedFee, fixedFeeWithDiscount: pixCreditFee.fixedFeeWithDiscount, discountExpiration: pixCreditFee.discountExpiration]
        customerInteractionService.saveUpdatePixCreditFee(pixCreditFee.customer, valuesChanged, fromAccountOwner)
    }

    private void onUpdatePixCreditPercentageFee(PixCreditFee pixCreditFee, Boolean fromAccountOwner) {
        Map valuesChanged = [percentageFee: pixCreditFee.percentageFee, minimumFee: pixCreditFee.minimumFee, maximumFee: pixCreditFee.maximumFee]
        customerInteractionService.saveUpdatePixCreditFee(pixCreditFee.customer, valuesChanged, fromAccountOwner)
    }

    private String buildSaveValidationMessage(Customer customer, PixCreditFee validatedPixCreditFee) {
        String validationMessage = DomainUtils.getValidationMessages(validatedPixCreditFee.errors)[0]
        validationMessage += " Customer [${customer.id}]"

        return validationMessage
    }
}
