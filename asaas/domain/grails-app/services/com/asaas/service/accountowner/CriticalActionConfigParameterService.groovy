package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class CriticalActionConfigParameterService {

    public static final List<String> ALLOWED_CRITICAL_ACTION_CONFIG_FIELD_LIST = ["transfer", "commercialInfoUpdate", "bankAccountUpdate", "userUpdate", "bill", "asaasCardRecharge", "asaasCardStatusManipulation", "mobilePhoneRecharge", "pixTransactionCreditRefund"]

    def childAccountParameterParserService
    def childAccountParameterInteractionService
    def childAccountParameterService
    def criticalActionConfigService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerCriticalActionConfig.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: childAccount.id]).get()
        if (!customerCriticalActionConfig) return

        Boolean parsedValue = childAccountParameterParserService.parse(childAccountParameter)

        if (parsedValue) {
            Map params = [(childAccountParameter.name): childAccountParameter.value, allowDisableCheckoutCriticalAction: true]
            criticalActionConfigService.update(childAccount, params, null)
            return
        }

        customerCriticalActionConfig."${childAccountParameter.name}" = parsedValue
        customerCriticalActionConfig.save(failOnError: true)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Boolean value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerCriticalActionConfig.simpleName, fieldName, value)

        if (!childAccountParameter.hasErrors()) childAccountParameterInteractionService.saveCriticalActionConfig(accountOwner, fieldName, value)

        return childAccountParameter
    }

    public void applyWhiteLabelCriticalActionConfig(Long customer, Boolean value) {
        List<String> criticalActionConfigList = ALLOWED_CRITICAL_ACTION_CONFIG_FIELD_LIST

        Boolean enabled = !value
        for (String criticalAction : criticalActionConfigList) {
            saveParameter(customer, criticalAction, enabled)
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Boolean value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        if (!ALLOWED_CRITICAL_ACTION_CONFIG_FIELD_LIST.contains(fieldName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração desse campo.")
        }

        return validatedParameter
    }
}
