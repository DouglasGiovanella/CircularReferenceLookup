package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class CustomerFeatureParameterService {

    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerFeatureService
    def customerInteractionService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerFeature.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Boolean enabled = Boolean.valueOf(childAccountParameter.value)

        customerFeatureService.toggleHandleBillingInfo(childAccount.id, enabled, true, true)
        customerInteractionService.saveToggleHandleBillingInfo(childAccount, enabled, true)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Boolean value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerFeature.simpleName, fieldName, value)

        childAccountParameterInteractionService.saveCanHandleBillingInfo(accountOwner, value)

        return childAccountParameter
    }

    private ChildAccountParameter validateParameter(Customer accountOwner) {
        ChildAccountParameter validatedParameter = new ChildAccountParameter()

        if (accountOwner.accountOwner) {
            DomainUtils.addError(validatedParameter, "Não é possível adicionar parâmetros para uma conta filha")
        }

        return validatedParameter
    }
}
