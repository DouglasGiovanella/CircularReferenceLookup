package com.asaas.service.accountowner

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerReceivableAnticipationConfigParameterService {

    def childAccountParameterInteractionService
    def childAccountParameterService
    def customerReceivableAnticipationConfigService
    def receivableAnticipationCustomerInteractionService

    public void applyAllParameters(Customer accountOwner, Customer childAccount) {
        List<ChildAccountParameter> childAccountParameterList = ChildAccountParameter.query([accountOwnerId: accountOwner.id, type: CustomerReceivableAnticipationConfig.simpleName]).list()
        for (ChildAccountParameter childAccountParameter : childAccountParameterList) {
            applyParameter(childAccount, childAccountParameter)
        }
    }

    public void applyParameter(Customer childAccount, ChildAccountParameter childAccountParameter) {
        String parameterName = childAccountParameter.name

        Object newParameterValue = childAccountParameter.value

        Object previousParameterValue = CustomerReceivableAnticipationConfig.query([column: parameterName, customerId: childAccount.id]).get()

        Map updatedMap = [:]

        updatedMap."${parameterName}Before" = previousParameterValue
        updatedMap."${parameterName}After" = newParameterValue

        customerReceivableAnticipationConfigService.setupChildAccount(childAccount, parameterName, newParameterValue)
        saveReceivableAnticipationCustomerInteraction(childAccount, parameterName, updatedMap)
    }

    public ChildAccountParameter saveParameter(Long accountOwnerId, String fieldName, Object value) {
        Customer accountOwner = Customer.get(accountOwnerId)

        ChildAccountParameter validatedParameter = validateParameter(accountOwner, fieldName, value)
        if (validatedParameter.hasErrors()) return validatedParameter

        ChildAccountParameter childAccountParameter = childAccountParameterService.saveOrUpdate(accountOwner, CustomerReceivableAnticipationConfig.simpleName, fieldName, value)

        if (childAccountParameter?.hasErrors()) return childAccountParameter

        childAccountParameterInteractionService.saveCustomerReceivableAnticipation(accountOwner, fieldName, value)

        return childAccountParameter
    }

    public void saveAllParameters(Long accountOwnerId, Map feeConfigs) {
        Customer accountOwner = Customer.read(accountOwnerId)
        if (accountOwner.accountOwner) {
            throw new BusinessException(Utils.getMessageProperty("customer.setChildAccountParameter.alreadyHasAccountOwner"))
        }

        for (fieldName in feeConfigs.keySet()) {
            BigDecimal fieldValue = feeConfigs.get(fieldName)
            ChildAccountParameter childAccountParameter = saveParameter(accountOwnerId, fieldName, fieldValue)
            if (childAccountParameter?.hasErrors()) throw new ValidationException("Não foi possível salvar as configurações de taxa de antecipação", childAccountParameter.errors)
        }
    }

    private ChildAccountParameter validateParameter(Customer accountOwner, String fieldName, Object value) {
        ChildAccountParameter validatedParameter = childAccountParameterService.validate(accountOwner, fieldName, value)

        if (CustomerReceivableAnticipationConfig.ENABLED_ANTICIPATION_CONFIG_LIST.contains(fieldName)) return validatedParameter

        if (validatedParameter.hasErrors()) return validatedParameter

        if (!CustomerReceivableAnticipationConfig.ALLOWED_ANTICIPATION_FEE_CONFIG_LIST.contains(fieldName)) {
            return DomainUtils.addError(validatedParameter, "Não é permitida a configuração do campo [${Utils.getMessageProperty("receivableAnticipationFeeDescription.${fieldName}")}]")
        }

        CustomerReceivableAnticipationConfig validatedDomain
        if (fieldName == "creditCardPercentage") {
            validatedDomain = customerReceivableAnticipationConfigService.validateCreditCardPercentage(accountOwner, value, fieldName)
        } else {
            validatedDomain = customerReceivableAnticipationConfigService.validateFeeValue(accountOwner.id, value, fieldName)
        }

        if (validatedDomain.hasErrors()) {
            return DomainUtils.copyAllErrorsFromObject(validatedDomain, validatedParameter)
        }

        return validatedParameter
    }

    private void saveReceivableAnticipationCustomerInteraction(Customer childAccount, String parameterName, Map updatedMap) {
        if (CustomerReceivableAnticipationConfig.ENABLED_ANTICIPATION_CONFIG_LIST.contains(parameterName)) {
            receivableAnticipationCustomerInteractionService.saveUpdateCustomerReceivableAnticipationConfigEnabled(childAccount, updatedMap)
        } else {
            receivableAnticipationCustomerInteractionService.saveUpdateCustomerReceivableAnticipationConfigFee(childAccount, updatedMap)
        }
    }
}
