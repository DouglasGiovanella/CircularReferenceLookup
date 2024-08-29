package com.asaas.service.customercreditcardabusepreventionparameter

import com.asaas.customer.CustomerCreditCardAbusePreventionParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerCreditCardAbusePreventionParameter
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerCreditCardAbusePreventionParameterService {

    def acquiringAbusePreventionParameterIntegrationAsyncActionService

    public CustomerCreditCardAbusePreventionParameter save(Customer customer, CustomerCreditCardAbusePreventionParameterName name, value) {
        validateSave(name, value)

        CustomerCreditCardAbusePreventionParameter customerCreditCardAbusePreventionParameter = CustomerCreditCardAbusePreventionParameter.query([customer: customer, name: name]).get()
        if (!customerCreditCardAbusePreventionParameter) {
            customerCreditCardAbusePreventionParameter = new CustomerCreditCardAbusePreventionParameter()
            customerCreditCardAbusePreventionParameter.customer = customer
            customerCreditCardAbusePreventionParameter.name = name
        }

        customerCreditCardAbusePreventionParameter."${buildField(name)}" = buildValue(name, value)

        customerCreditCardAbusePreventionParameter.save(failOnError: true)

        acquiringAbusePreventionParameterIntegrationAsyncActionService.save(customer.id)

        return customerCreditCardAbusePreventionParameter
    }

    public Integer getParameterIntegerValue(Customer customer, CustomerCreditCardAbusePreventionParameterName parameterName) {
        Integer paramIntegerValue = CustomerCreditCardAbusePreventionParameter.queryIntegerValue([disableSort: true, customer: customer, name: parameterName]).get()

        if (paramIntegerValue == null) return buildIntegerDefaultValue(customer, parameterName)

        return paramIntegerValue
    }

    private void validateSave(CustomerCreditCardAbusePreventionParameterName name, value) {
        if (name.isIntegerValue() && value?.toString()?.isInteger()) return
        if (name.isBooleanValue() && Utils.isValidBooleanValue(value)) return
        if (name.isStringValue() && value instanceof String) return

        throw new BusinessException("Valor informado é inválido.")
    }

    private Object buildValue(CustomerCreditCardAbusePreventionParameterName name, value) {
        if (name.isIntegerValue()) return Utils.toInteger(value)
        if (name.isBooleanValue()) return Utils.toBoolean(value)

        return value
    }

    private String buildField(CustomerCreditCardAbusePreventionParameterName name) {
        if (name.isIntegerValue()) return "integerValue"
        if (name.isBooleanValue()) return "value"
        if (name.isStringValue()) return "stringValue"

        throw new BusinessException("Campo inválido.")
    }

    private Integer buildIntegerDefaultValue(Customer customer, CustomerCreditCardAbusePreventionParameterName parameterName) {
        if (parameterName.isApprovalRateMinimumValue()) return customer.isLegalPerson() ? 10 : 20

        return parameterName.defaultValue
    }
}
