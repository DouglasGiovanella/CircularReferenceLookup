package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerTransferConfig
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerTransferConfigParameterService {

    private static final List<String> ALLOWED_CUSTOMER_TRANSFER_CONFIG_FIELD_LIST = ["monthlyQuantityPixWithoutFee", "mustConsiderTedInMonthlyQuantityPixWithoutFee"]

    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerTransferConfigService

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.read(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerTransferConfig.simpleName, fieldName, value)
        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.saveCustomerTransferConfig(accountOwner, fieldName, value)

        return childAccountParameter
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        customerTransferConfigService.save(childAccount, [(childAccountParameter.name): parseParameterValue(childAccountParameter.name, childAccountParameter.value), fromAccountOwner: true])
    }

    private Object parseParameterValue(String fieldName, String value) {
        switch (fieldName) {
            case "monthlyQuantityPixWithoutFee":
                return Utils.toInteger(value)
            case "mustConsiderTedInMonthlyQuantityPixWithoutFee":
                return Utils.toBoolean(value)
            default:
                throw new NotImplementedException("Não implementado para o campo ${fieldName}")
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Object value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!ALLOWED_CUSTOMER_TRANSFER_CONFIG_FIELD_LIST.contains(fieldName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse campo.")
        }

        return validatedParameter
    }
}
