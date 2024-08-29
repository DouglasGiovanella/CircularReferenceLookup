package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class CreditCardFeeConfigParameterService {

    public static final List<String> CREDIT_CARD_FEE_DISCOUNT_EXPIRATION_TYPE_LIST = ["discountExpiration", "discountExpirationInMonths"]

    def creditCardFeeConfigService
    def customerInteractionService
    def childAccountParameterService
    def childAccountParameterInteractionService
    def creditCardFeeConfigAdminService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CreditCardFeeConfig.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Map feeConfig = [:]
        String fieldName = buildFieldName(childAccountParameter)
        Object fieldValue = buildFieldValue(childAccountParameter)

        feeConfig."${fieldName}" = fieldValue
        creditCardFeeConfigService.save(childAccount, feeConfig)
        customerInteractionService.saveUpdateCreditCardFee(childAccount, feeConfig, true)
    }

    public void saveParameter(Long accountOwnerId, Map creditCardFeeConfig) {
        Customer accountOwner = Customer.read(accountOwnerId)

        ChildAccountParameter validatedParameters = validateParameters(accountOwner, creditCardFeeConfig)
        if (validatedParameters.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de taxa de cartão de crédito", validatedParameters.errors)

        creditCardFeeConfig.each { saveOrUpdateCreditCardConfig(accountOwner, it.key, it.value) }
        childAccountParameterInteractionService.saveCreditCardFee(accountOwner, creditCardFeeConfig)
    }

    public Map parseCreditCardFeeConfigForSave(Map params) {
        Map creditCardFeeConfig = [
            "fixedFee": Utils.toBigDecimal(params.fixedFee),
            "upfrontFee": Utils.toBigDecimal(params.upfrontFee),
            "discountUpfrontFee": Utils.toBigDecimal(params.discountUpfrontFee),
            "upToSixInstallmentsFee": Utils.toBigDecimal(params.upToSixInstallmentsFee),
            "discountUpToSixInstallmentsFee": Utils.toBigDecimal(params.discountUpToSixInstallmentsFee),
            "upToTwelveInstallmentsFee": Utils.toBigDecimal(params.upToTwelveInstallmentsFee),
            "discountUpToTwelveInstallmentsFee": Utils.toBigDecimal(params.discountUpToTwelveInstallmentsFee),
            "discountExpiration": CustomDateUtils.fromString(params.discountExpiration),
            "discountExpirationInMonths": params.discountExpirationInMonths ? Utils.toInteger(params.discountExpirationInMonths) : null
        ]

        return creditCardFeeConfig
    }

    public String buildFieldName(ChildAccountParameter childAccountParameter) {
        String fieldName = childAccountParameter.name

        if (CreditCardFeeConfigParameterService.CREDIT_CARD_FEE_DISCOUNT_EXPIRATION_TYPE_LIST.contains(fieldName)) {
            fieldName = "discountExpiration"
        }

        return fieldName
    }

    public Object buildFieldValue(ChildAccountParameter childAccountParameter) {
        final String discountExpirationFieldName = "discountExpiration"
        final String discountExpirationInMonthsName = "discountExpirationInMonths"

        if (childAccountParameter.name == discountExpirationFieldName) return CustomDateUtils.fromStringDatabaseDateFormat(childAccountParameter.value)
        if (childAccountParameter.name == discountExpirationInMonthsName && childAccountParameter.value) return CustomDateUtils.addMonths(new Date().clearTime(), Utils.toInteger(childAccountParameter.value))

        return parseCreditCardFeeConfigValueForApply(childAccountParameter.name, childAccountParameter.value)
    }

    private void saveOrUpdateCreditCardConfig(Customer accountOwner, String fieldName, Object fieldValue) {
        ChildAccountParameter currentChildAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: "CreditCardFeeConfig", name: fieldName]).get()

        Object parsedFieldValue = fieldValue instanceof Date ? CustomDateUtils.fromDate(fieldValue, CustomDateUtils.DATABASE_DATETIME_FORMAT) : fieldValue

        Boolean hasNewParameterToSave = !currentChildAccountParameter && !Utils.isEmptyOrNull(parsedFieldValue)

        Boolean hasNullableDiscount = !currentChildAccountParameter && Utils.isEmptyOrNull(parsedFieldValue) && CreditCardFeeConfig.NULLABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST.contains(fieldName)
        if (hasNewParameterToSave || hasNullableDiscount) {
            childAccountParameterService.save(accountOwner, "CreditCardFeeConfig", fieldName, parsedFieldValue)
            return
        }

        Boolean withoutUpdateCreditCardFeeConfig = Utils.isEmptyOrNull(parsedFieldValue) && !CreditCardFeeConfig.NULLABLE_CREDIT_CARD_FEE_CONFIG_FIELD_LIST.contains(fieldName)
        if (withoutUpdateCreditCardFeeConfig) return

        Boolean shouldDeleteParameterCreditCardConfigConfig = currentChildAccountParameter && Utils.isEmptyOrNull(parsedFieldValue)
        if (shouldDeleteParameterCreditCardConfigConfig) childAccountParameterService.delete(currentChildAccountParameter)

        currentChildAccountParameter.value = parsedFieldValue
        currentChildAccountParameter.save(failOnError: true)
    }

    private ChildAccountParameter validateParameters(Customer customer, Map creditCardFeeConfig) {
        ChildAccountParameter validatedParameters = new ChildAccountParameter()

        if (customer.accountOwner) {
            return DomainUtils.addError(validatedParameters, Utils.getMessageProperty("customer.setChildAccountParameter.alreadyHasAccountOwner"))
        }

        List<String> allowedFieldList = CreditCardFeeConfig.ALLOWED_CREDIT_CARD_FEE_CONFIG_FIELD_LIST + CreditCardFeeConfig.ALLOWED_DEBIT_CARD_FEE_CONFIG_FIELD_LIST
        for (fieldName in creditCardFeeConfig.keySet()) {
            if (!allowedFieldList.contains(fieldName)) {
                return DomainUtils.addError(validatedParameters, "Não é permitida a configuração do campo [${Utils.getMessageProperty("creditCardFeeConfig.${fieldName}.label")}].")
            }
        }

        Integer discountExpirationInMonthsLimit = CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS
        if (creditCardFeeConfig.discountExpirationInMonths > discountExpirationInMonthsLimit) {
            return DomainUtils.addError(validatedParameters, "Não é possível conceder mais que ${CreditCardFeeConfig.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS} meses para a data de validade da taxa promocional do cliente.")
        }

        CreditCardFeeConfig validatedCreditCardFeeConfig = creditCardFeeConfigAdminService.validateCreditCardFee(customer.id, new CreditCardFeeConfig(), creditCardFeeConfig)
        DomainUtils.copyAllErrorsFromObject(validatedCreditCardFeeConfig, validatedParameters)

        return validatedParameters
    }

    private Object parseCreditCardFeeConfigValueForApply(String fieldName, String value) {
        switch (fieldName) {
            case "fixedFee":
            case "upfrontFee":
            case "upToSixInstallmentsFee":
            case "upToTwelveInstallmentsFee":
            case "debitCardFixedFee":
            case "debitCardFee":
                return Utils.toBigDecimal(value)
            case "discountExpiration":
                return CustomDateUtils.fromStringDatabaseDateFormat(value)
            case "discountExpirationInMonths":
                return Utils.toInteger(value)
            case "discountUpfrontFee":
            case "discountUpToSixInstallmentsFee":
            case "discountUpToTwelveInstallmentsFee":
                Object parsedValue = null
                if (value) parsedValue = Utils.toBigDecimal(value)
                return parsedValue
            default:
                throw new NotImplementedException()
        }
    }
}
