package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerConfigParameterService {

    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerConfigService
    def customerInteractionService
    def customerCheckoutLimitService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerConfig.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        String fieldName = childAccountParameter.name
        Object value = parseParameterValue(childAccountParameter.name, childAccountParameter.value)

        switch (fieldName) {
            case "overrideAsaasSoftDescriptor":
                customerConfigService.saveOverrideAsaasSoftDescriptor(childAccount.id, value, true)
                break
            case "clearSaleDisabled":
                customerConfigService.saveToggleClearSale(childAccount.id, value, true)
                break
            case "dailyCheckoutLimit":
                customerCheckoutLimitService.setDailyLimit(childAccount, value, "limite alterado via conta pai")
                break
            default:
                throw new NotImplementedException()
        }
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        Object parsedValue = parseParameterValue(fieldName, value)
        ChildAccountParameter validatedParameter = validateParameter(accountOwner)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerConfig.simpleName, fieldName, parsedValue)
        childAccountParameterInteractionService.saveCustomerConfigParameter(accountOwner, fieldName, parsedValue)

        return childAccountParameter
    }

    public Object parseParameterValue(String fieldName, String value) {
        switch (fieldName) {
            case "overrideAsaasSoftDescriptor":
            case "clearSaleDisabled":
                return Boolean.valueOf(value)
            case "dailyCheckoutLimit":
                return new BigDecimal(value)
            default:
                throw new NotImplementedException()
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner) {
        ChildAccountParameter validatedParameter = new ChildAccountParameter()

        if (accountOwner.accountOwner) {
            DomainUtils.addError(validatedParameter, "Não é possível adicionar parâmetros para uma conta filha")
        }

        return validatedParameter
    }
}
