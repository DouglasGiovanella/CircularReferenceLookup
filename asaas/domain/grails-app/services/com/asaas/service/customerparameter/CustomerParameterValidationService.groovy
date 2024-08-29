package com.asaas.service.customerparameter

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSegment
import com.asaas.domain.subscription.Subscription
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerParameterValidationService {

    def grailsApplication

    public BusinessValidation validateMinimumValueToForceRiskValidation(CustomerParameterName name, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.MINIMUM_VALUE_TO_FORCE_RISK_VALIDATION) return businessValidation

        BigDecimal minValue = 1.00
        BigDecimal maxValue = 400.00

        BigDecimal parameterValue = Utils.toBigDecimal(value)
        if (parameterValue < minValue || parameterValue > maxValue) {
            businessValidation.addError("customerParameter.minimumValueToForceRiskValidation.invalidValue", [minValue, maxValue])
        }

        return businessValidation
    }

    public BusinessValidation validateCustomDaysToCreateSubscriptionPayments(CustomerParameterName name, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT) return businessValidation

        Integer parameterValue = Utils.toInteger(value)
        if (!Subscription.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT.contains(parameterValue)) {
            businessValidation.addError(("customerParameter.customDaysBeforeToCreateSubscriptionPayment.invalidValue"), [Subscription.CUSTOM_DAYS_BEFORE_TO_CREATE_SUBSCRIPTION_PAYMENT])
        }

        return businessValidation
    }

    public BusinessValidation validateIgnoreCustomerSegmentOnCreditCardRiskValidationRule(CustomerSegment customerSegment, CustomerParameterName name) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.IGNORE_CUSTOMER_SEGMENT_ON_CREDIT_CARD_RISK_VALIDATION_RULE) return businessValidation

        if (!customerSegment.isCorporate()) {
            businessValidation.addError("customerParameter.onlyAllowedForCustomersToCorporateSegment")
        }

        return businessValidation
    }

    public BusinessValidation validateCustomCreditCardSettlementDays(CustomerParameterName name, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.CUSTOM_CREDIT_CARD_SETTLEMENT_DAYS) return businessValidation

        Integer minDays = grailsApplication.config.payment.creditCard.minDaysAlowedToCredit
        Integer maxDays = grailsApplication.config.payment.creditCard.daysToCredit

        Integer parameterValue = Utils.toInteger(value)
        if (parameterValue < minDays || parameterValue > maxDays) {
            businessValidation.addError("customerParameter.customCreditCardSettlementDays.invalidValue", [minDays, maxDays])
        }

        return businessValidation
    }

    public BusinessValidation validateMinimumDetachedBankSlipAndPixValue(CustomerParameterName name, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.CUSTOM_MINIMUM_VALUE_TO_BANK_SLIP_AND_PIX) return businessValidation

        BigDecimal minValue = 0.01
        BigDecimal parameterValue = Utils.toBigDecimal(value)
        if (parameterValue < minValue) {
            businessValidation.addError("customerParameter.parametersWithMinimumValue.invalidValue")
        }

        return businessValidation
    }

    public BusinessValidation validateMinimumDetachedCreditCardValue(CustomerParameterName name, value) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (name != CustomerParameterName.MINIMUM_DETACHED_CREDIT_CARD_VALUE) return businessValidation

        BigDecimal minValue = 0.01
        BigDecimal parameterValue = Utils.toBigDecimal(value)
        if (parameterValue < minValue) {
            businessValidation.addError("customerParameter.parametersWithMinimumValue.invalidValue")
        }

        return businessValidation
    }
}
