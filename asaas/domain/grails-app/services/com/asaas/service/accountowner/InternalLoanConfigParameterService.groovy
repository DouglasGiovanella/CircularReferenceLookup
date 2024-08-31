package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.internalloan.InternalLoanConfig
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class InternalLoanConfigParameterService {

    def internalLoanConfigService
    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerInteractionService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: InternalLoanConfig.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        Boolean enabled = Boolean.valueOf(childAccountParameter.value)

        internalLoanConfigService.saveOrUpdate(childAccount.accountOwner, childAccount, enabled)
        customerInteractionService.saveUpdateInternalLoanConfig(childAccount, enabled)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, Boolean enabled) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, InternalLoanConfig.simpleName, "enabled", enabled)

        childAccountParameterInteractionService.saveInternalLoanConfig(accountOwner, enabled)

        return childAccountParameter
    }

    private ChildAccountParameter validateParameter(Customer accountOwner) {
        ChildAccountParameter validatedParameter = new ChildAccountParameter()

        if (accountOwner.accountOwner) {
            DomainUtils.addError(validatedParameter, "Não é possível adicionar parâmetros para uma conta filha")
        }

        return  validatedParameter
    }
}
