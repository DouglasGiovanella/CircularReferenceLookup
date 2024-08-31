package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.BankSlipFee
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class BankSlipFeeParameterService {
    public static final List<String> ALLOWED_BANK_SLIP_FEE_CONFIG_FIELD_LIST = ["defaultValue", "discountValue", "discountExpirationDate", "discountExpirationInMonths"]
    public static final List<String> DISCOUNT_EXPIRATION_TYPE_LIST = ["discountExpirationDate", "discountExpirationInMonths"]

    def bankSlipFeeService
    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerInteractionService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: BankSlipFee.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Map bankSlipFeeConfig = [:]
        String fieldName = childAccountParameter.name
        Object fieldValue = parseBankSlipFeeConfigValueForApply(fieldName, childAccountParameter.value)

        if (DISCOUNT_EXPIRATION_TYPE_LIST.contains(fieldName)) {
            fieldValue = fieldName == "discountExpirationDate" ? fieldValue : CustomDateUtils.addMonths(new Date().clearTime(), fieldValue)
            fieldName = "discountExpiration"
        }

        bankSlipFeeConfig."${fieldName}" = fieldValue
        bankSlipFeeService.setupChildAccount(childAccount, bankSlipFeeConfig)
        customerInteractionService.saveUpdateBankSlipFee(childAccount, bankSlipFeeConfig, true)
    }

    public void saveParameter(Long accountOwnerId, Map bankSlipFeeConfig) {
        Customer accountOwner = Customer.get(accountOwnerId)
        ChildAccountParameter validatedParameters = validateParameters(accountOwner, bankSlipFeeConfig)
        if (validatedParameters.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de boleto", validatedParameters.errors)

        bankSlipFeeConfig.each { saveBankSlipFeeConfig(accountOwner, it.key, it.value) }
        childAccountParameterInteractionService.saveBankSlipFee(accountOwner, bankSlipFeeConfig)
    }

    public Object parseBankSlipFeeConfigValueForApply(String fieldName, value) {
        switch (fieldName) {
            case "defaultValue":
            case "discountValue":
                return Utils.toBigDecimal(value)
            case "discountExpirationDate":
                return CustomDateUtils.fromStringDatabaseDateFormat(value)
            case "discountExpirationInMonths":
                return Utils.toInteger(value)
            default:
                throw new NotImplementedException()
        }
    }

    public Map parseBankSlipFeeConfigForSave(String defaultValue, String discountValue, String discountExpirationDate, String discountExpirationInMonths) {
        Map bankSlipFeeConfig = [
            "defaultValue": Utils.toBigDecimal(defaultValue),
            "discountValue": Utils.toBigDecimal(discountValue),
            "discountExpirationDate": CustomDateUtils.fromString(discountExpirationDate),
            "discountExpirationInMonths": discountExpirationInMonths ? Utils.toInteger(discountExpirationInMonths) : null
        ]

        return bankSlipFeeConfig
    }

    private void saveBankSlipFeeConfig(Customer accountOwner, String fieldName, fieldValue) {
        Object parsedFieldValue = fieldValue instanceof Date ? CustomDateUtils.fromDate(fieldValue, CustomDateUtils.DATABASE_DATETIME_FORMAT) : fieldValue

        childAccountParameterService.saveOrUpdate(accountOwner, BankSlipFee.simpleName, fieldName, parsedFieldValue)
    }

    private ChildAccountParameter validateParameters(Customer customer, Map bankSlipFeeConfig) {
        ChildAccountParameter validatedParameters = new ChildAccountParameter()
        BusinessValidation businessValidation = new BusinessValidation()
        Date discountExpirationDate = bankSlipFeeConfig.discountExpirationDate

        if (customer.accountOwner) {
            businessValidation.addError("customer.setChildAccountParameter.alreadyHasAccountOwner")
            return DomainUtils.addError(validatedParameters, businessValidation.getFirstErrorMessage())
        }

        for (fieldName in bankSlipFeeConfig.keySet()) {
            if (!ALLOWED_BANK_SLIP_FEE_CONFIG_FIELD_LIST.contains(fieldName)) {
                return DomainUtils.addError(validatedParameters, "Não é permitida a configuração desse campo.")
            }
        }

        if (bankSlipFeeConfig.discountExpirationDate || bankSlipFeeConfig.discountExpirationInMonths) {
            if (bankSlipFeeConfig.discountExpirationDate && bankSlipFeeConfig.discountExpirationInMonths) {
                businessValidation.addError("bankSlipFee.discountExpiration.twoDiscountExpirations")
                return DomainUtils.addError(validatedParameters, businessValidation.getFirstErrorMessage())
            }

            if (bankSlipFeeConfig.discountExpirationInMonths) {
                if (bankSlipFeeConfig.discountExpirationInMonths < 1) {
                    businessValidation.addError("bankSlipFee.discountExpirationInMonths.invalidMonth")
                    return DomainUtils.addError(validatedParameters, businessValidation.getFirstErrorMessage())
                }

                discountExpirationDate = CustomDateUtils.addMonths(new Date().clearTime(), bankSlipFeeConfig.discountExpirationInMonths)
            }
        }

        businessValidation = bankSlipFeeService.validateUpdate(customer.id, bankSlipFeeConfig.defaultValue, bankSlipFeeConfig.discountValue, discountExpirationDate, null)
        if (!businessValidation.isValid()) {
            return DomainUtils.addError(validatedParameters, businessValidation.getFirstErrorMessage())
        }

        return validatedParameters
    }
}
