package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class CustomerFeeParameterService {

    def childAccountParameterInteractionService
    def childAccountParameterService
    def feeAdminService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerFee.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Object value = parseParameterValue(childAccountParameter.name, childAccountParameter.value)
        feeAdminService.updateFee(childAccount.id, [(childAccountParameter.name): value], false)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        Object parsedValue = parseParameterValue(fieldName, value)
        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, parsedValue)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerFee.simpleName, fieldName, parsedValue)

        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.saveCustomerFee(accountOwner, fieldName, parsedValue)

        return childAccountParameter
    }

    public Object parseParameterValue(String fieldName, String value) {
        switch (fieldName) {
            case "invoiceValue":
            case "productInvoiceValue":
            case "consumerInvoiceValue":
            case "dunningCreditBureauFeeValue":
            case "creditBureauReportNaturalPersonFee":
            case "creditBureauReportLegalPersonFee":
            case "pixDebitFee":
            case "pixCreditFee":
            case "transferValue":
            case "paymentMessagingNotificationFeeValue":
            case "paymentSmsNotificationFeeValue":
            case "whatsappNotificationFee":
            case "childAccountKnownYourCustomerFee":
            case "phoneCallNotificationFee":
                return Utils.toBigDecimal(value)
            case "alwaysChargeTransferFee":
                return Utils.toBoolean(value)
            default:
                throw new NotImplementedException()
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Object value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!CustomerFee.ALLOWED_CUSTOMER_FEE_CONFIG_FIELD_LIST.contains(fieldName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse campo.")
        }

        return validatedParameter
    }
}
