package com.asaas.service.customeraccount

import com.asaas.customeraccount.CustomerAccountParameterName
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customeraccount.CustomerAccountParameter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerAccountParameterService {

    public void save(CustomerAccount customerAccount, CustomerAccountParameterName name, Object value) {
        CustomerAccountParameter validatedDomain = validateSave(customerAccount, name)
        if (validatedDomain.hasErrors()) throw new ValidationException("Ocorreu um erro ao salvar o parâmetro de pagador", validatedDomain.errors)

        CustomerAccountParameter parameter = new CustomerAccountParameter()
        parameter.name = name
        parameter.customerAccount = customerAccount
        parameter.value = value?.toString()
        parameter.save(failOnError: true)
    }

    public void update(CustomerAccountParameter parameter, Object value) {
        parameter.value = value?.toString()
        parameter.save(failOnError: true)
    }

    public void disableIfPossible(CustomerAccount customerAccount, CustomerAccountParameterName name) {
        CustomerAccountParameter existingParameter = CustomerAccountParameter.query([customerAccountId: customerAccount.id, name: name]).get()
        Boolean hasParameterActive = existingParameter?.parseBooleanValue()
        if (hasParameterActive) update(existingParameter, false)
    }

    private CustomerAccountParameter validateSave(CustomerAccount customerAccount, CustomerAccountParameterName name) {
        CustomerAccountParameter validatedDomain = new CustomerAccountParameter()

        Boolean parameterAlreadyExists = CustomerAccountParameter.query([exists: true, customerAccountId: customerAccount.id, name: name]).get().asBoolean()
        if (parameterAlreadyExists) {
            String parameterLabel = Utils.getMessageProperty("CustomerAccountParameterName.${name.toString()}.label")
            DomainUtils.addError(validatedDomain, "Este pagador já possui uma configuração para o parâmetro: ${parameterLabel}")
        }

        return validatedDomain
    }
}
