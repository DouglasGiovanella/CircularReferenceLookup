package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixCreditFee
import com.asaas.pix.PixCreditFeeType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class PixCreditFeeParameterService {

    public static final List<String> ALLOWED_FEE_CONFIG_FIELD_LIST = ["type", "fixedFee", "fixedFeeWithDiscount", "discountExpirationDate", "discountExpirationInMonths", "percentageFee", "minimumFee", "maximumFee"]
    public static final List<String> ALLOWED_FIXED_FEE_CONFIG_FIELD_LIST = ["fixedFee", "fixedFeeWithDiscount", "discountExpirationDate", "discountExpirationInMonths"]
    public static final List<String> ALLOWED_PERCENTAGE_FEE_CONFIG_FIELD_LIST = ["percentageFee", "minimumFee", "maximumFee"]
    public static final List<String> FIXED_FEE_DISCOUNT_EXPIRATION_TYPE_LIST = ["discountExpirationDate", "discountExpirationInMonths"]

    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerInteractionService
    def pixCreditFeeService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: PixCreditFee.simpleName]).list()

        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Map pixCreditFeeConfig = [:]
        String fieldName = childAccountParameter.name
        Object fieldValue = parsePixCreditFeeConfigValueForApply(fieldName, childAccountParameter.value)

        if (PixCreditFeeParameterService.FIXED_FEE_DISCOUNT_EXPIRATION_TYPE_LIST.contains(fieldName)) {
            fieldValue = fieldName == "discountExpirationDate" ? fieldValue : CustomDateUtils.addMonths(new Date().clearTime(), fieldValue)
            fieldName = "discountExpiration"
        }

        pixCreditFeeConfig."${fieldName}" = fieldValue
        pixCreditFeeService.setupChildAccount(childAccount, pixCreditFeeConfig)
        customerInteractionService.saveUpdatePixCreditFee(childAccount, pixCreditFeeConfig, true)
    }

    public void saveParameter(Long accountOwnerId, Map pixCreditFeeConfig) {
        Customer accountOwner = Customer.get(accountOwnerId)
        ChildAccountParameter validatedParameters = validateParameters(accountOwner, pixCreditFeeConfig)
        if (validatedParameters.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de taxas de crédito de Pix", validatedParameters.errors)

        pixCreditFeeConfig.each { String key, value ->
            savePixCreditFeeConfig(accountOwner, key, value)
        }

        childAccountParameterInteractionService.savePixCreditFee(accountOwner, pixCreditFeeConfig)
    }

    public Map parsePixCreditFeeConfigForSave(String type, String fixedFee, String fixedFeeWithDiscount, String discountExpirationDate, String discountExpirationInMonths, String percentageFee, String minimumFee, String maximumFee) {
        PixCreditFeeType pixCreditFeeType = PixCreditFeeType.convert(type)
        Map pixCreditFeeConfig = [type: type]

        if (pixCreditFeeType.isFixed()) {
            pixCreditFeeConfig.fixedFee = Utils.toBigDecimal(fixedFee)
            pixCreditFeeConfig.fixedFeeWithDiscount = Utils.toBigDecimal(fixedFeeWithDiscount)
            pixCreditFeeConfig.discountExpirationDate = CustomDateUtils.fromString(discountExpirationDate)
            pixCreditFeeConfig.discountExpirationInMonths = discountExpirationInMonths ? Utils.toInteger(discountExpirationInMonths) : null
            pixCreditFeeConfig.percentageFee = null
            pixCreditFeeConfig.minimumFee = null
            pixCreditFeeConfig.maximumFee = null
        } else {
            pixCreditFeeConfig.percentageFee = Utils.toBigDecimal(percentageFee)
            pixCreditFeeConfig.minimumFee = Utils.toBigDecimal(minimumFee)
            pixCreditFeeConfig.maximumFee = Utils.toBigDecimal(maximumFee)
            pixCreditFeeConfig.fixedFee = null
            pixCreditFeeConfig.fixedFeeWithDiscount = null
            pixCreditFeeConfig.discountExpirationDate = null
            pixCreditFeeConfig.discountExpirationInMonths = null
        }

        return pixCreditFeeConfig
    }

    public Object parsePixCreditFeeConfigValueForApply(String fieldName, value) {
        switch (fieldName) {
            case "type":
                return PixCreditFeeType.convert(value)
            case "fixedFee":
            case "fixedFeeWithDiscount":
            case "percentageFee":
            case "minimumFee":
            case "maximumFee":
                return Utils.toBigDecimal(value)
            case "discountExpirationDate":
                return CustomDateUtils.fromStringDatabaseDateFormat(value)
            case "discountExpirationInMonths":
                return Utils.toInteger(value)
            default:
                throw new NotImplementedException()
        }
    }

    private void savePixCreditFeeConfig(Customer accountOwner, String fieldName, fieldValue) {
        Object parsedFieldValue = fieldValue instanceof Date ? CustomDateUtils.fromDate(fieldValue, CustomDateUtils.DATABASE_DATETIME_FORMAT) : fieldValue
        childAccountParameterService.saveOrUpdate(accountOwner, PixCreditFee.simpleName, fieldName, parsedFieldValue)
    }

    private ChildAccountParameter validateParameters(Customer customer, Map pixCreditFeeConfig) {
        ChildAccountParameter validatedParameters = new ChildAccountParameter()

        if (customer.accountOwner) {
            return DomainUtils.addError(validatedParameters, Utils.getMessageProperty("customer.setChildAccountParameter.alreadyHasAccountOwner"))
        }

        PixCreditFeeType pixCreditFeeType = PixCreditFeeType.convert(pixCreditFeeConfig.type)
        PixCreditFee validatedPixCreditFee

        if (pixCreditFeeType.isFixed()) {
            Date discountExpirationDate = pixCreditFeeConfig.discountExpirationDate

            if (pixCreditFeeConfig.discountExpirationDate || pixCreditFeeConfig.discountExpirationInMonths) {
                if (pixCreditFeeConfig.discountExpirationDate && pixCreditFeeConfig.discountExpirationInMonths) {
                    return DomainUtils.addError(validatedParameters, "Não é possível adicionar a data de expiração do desconto e o número de meses para expiração do desconto ao mesmo tempo.")
                }

                if (pixCreditFeeConfig.discountExpirationInMonths) {
                    if (pixCreditFeeConfig.discountExpirationInMonths < 1) {
                        return DomainUtils.addError(validatedParameters, "É necessário informar um número de meses igual ou superior a 1.")
                    }

                    discountExpirationDate = CustomDateUtils.addMonths(new Date().clearTime(), pixCreditFeeConfig.discountExpirationInMonths)
                }
            }

            validatedPixCreditFee = pixCreditFeeService.validateSaveFixedFee(customer, pixCreditFeeConfig.fixedFee, pixCreditFeeConfig.fixedFeeWithDiscount, discountExpirationDate)
        } else {
            validatedPixCreditFee = pixCreditFeeService.validateSavePercentageFee(pixCreditFeeConfig.percentageFee, pixCreditFeeConfig.minimumFee, pixCreditFeeConfig.maximumFee)
        }

        if (validatedPixCreditFee.hasErrors()) return DomainUtils.addError(validatedParameters, DomainUtils.getValidationMessages(validatedPixCreditFee.errors)[0])

        return validatedParameters
    }
}
