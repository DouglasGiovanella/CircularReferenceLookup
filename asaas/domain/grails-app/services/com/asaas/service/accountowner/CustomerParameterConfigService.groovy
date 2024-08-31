package com.asaas.service.accountowner

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class CustomerParameterConfigService {
    def childAccountParameterInteractionService
    def childAccountParameterService
    def criticalActionConfigParameterService
    def customerParameterService
    def internalLoanConfigParameterService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerParameter.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        CustomerParameterName convertedChildAccountParameter = CustomerParameterName.convert(childAccountParameter.name)
        if (!convertedChildAccountParameter) {
            AsaasLogger.warn("O parâmetro ${childAccountParameter.name} está depreciado, portanto não será replicado para as contas filhas.")
        } else {
            customerParameterService.save(childAccount, convertedChildAccountParameter, parseCustomerParameterValue(convertedChildAccountParameter.valueType, childAccountParameter.value))
        }
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, CustomerParameterName customerParameterName, parameterValue) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, customerParameterName, parameterValue)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerParameter.simpleName, customerParameterName.toString(), parseCustomerParameterValue(customerParameterName.valueType, parameterValue))

        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.saveCustomerParameterConfig(accountOwner, customerParameterName, parameterValue)

        return childAccountParameter
    }

    public Object parseCustomerParameterValue(Class valueType, value) {
        switch (valueType) {
            case BigDecimal:
                return Utils.toBigDecimal(value)
            case Boolean:
                return Utils.toBoolean(value)
            case String:
                return value
            default:
                throw new NotImplementedException()
        }
    }

    public void toggleWhiteLabelToChildAccountAndAssociatedAccountOwnerParameters(Long customerId, Boolean value) {
        customerParameterService.saveAccountOwnerWhiteLabelParameters(customerId, value)
        criticalActionConfigParameterService.applyWhiteLabelCriticalActionConfig(customerId, value)
        saveChildAccountWhiteLabelParameters(customerId, value)
        internalLoanConfigParameterService.saveParameter(customerId, value)
    }

    public void saveChildAccountWhiteLabelParameters(Long customer, Boolean value) {
        List<CustomerParameterName> whiteLabelParameterList = CustomerParameterName.listChildAccountWhiteLabelParameter()

        for (CustomerParameterName parameter : whiteLabelParameterList) {
            saveParameter(customer, parameter, value)
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, CustomerParameterName customerParameterName, parameterValue) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, customerParameterName.toString(), parameterValue)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!customerParameterName) {
            return DomainUtils.addError(validatedParameter, "Parâmetro não encontrado.")
        }

        if (!CustomerParameterName.listForChildAccountParameterConfig().contains(customerParameterName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse parâmetro.")
        }

        def parsedValue = parseCustomerParameterValue(customerParameterName.valueType, parameterValue)
        if (parsedValue?.class != customerParameterName.valueType) {
            return DomainUtils.addError(validatedParameter, "O tipo de dado enviado não é compatível com o tipo de dado do parâmetro.")
        }

        BusinessValidation businessValidation = customerParameterService.validateParameterValue(accountOwner.segment, customerParameterName, parsedValue)
        if (!businessValidation.isValid()) {
            return DomainUtils.addError(validatedParameter, businessValidation.getFirstErrorMessage())
        }

        return validatedParameter
    }
}
