package com.asaas.service.customerdatarestriction

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdatarestriction.CustomerDataRestrictionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdatarestriction.CustomerDataRestriction
import com.asaas.pix.PixAccountConfirmedFraudType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerDataRestrictionService {

    def asyncActionService

    public CustomerDataRestriction save(Map params) {
        Map parsedParams = parseParams(params)

        CustomerDataRestriction validatedCustomerDataRestriction = validateSave(parsedParams)
        if (validatedCustomerDataRestriction.hasErrors()) throw new ValidationException(null, validatedCustomerDataRestriction.errors)

        CustomerDataRestriction customerDataRestriction = new CustomerDataRestriction()
        customerDataRestriction.type = parsedParams.type
        customerDataRestriction.value = parsedParams.value
        customerDataRestriction.save(failOnError: true)

        if (parsedParams.isAsaasCustomer) {
            asyncActionService.save(AsyncActionType.SAVE_PIX_ACCOUNT_CONFIRMED_FRAUD, [customerId: parsedParams.customerId, fraudType: parsedParams.fraudType, pixKey: parsedParams.pixKey])
        }

        return customerDataRestriction
    }

    public CustomerDataRestriction delete(CustomerDataRestriction customerDataRestriction) {
        if (customerDataRestriction.deleted) return customerDataRestriction

        customerDataRestriction.deleted = true
        customerDataRestriction.save(failOnError: true)

        if (customerDataRestriction.type.isCpfCnpj()) {
            Long customerId = Customer.query([column: "id", cpfCnpj: customerDataRestriction.value]).get()
            if (customerId) asyncActionService.save(AsyncActionType.CANCEL_PIX_ACCOUNT_CONFIRMED_FRAUD, [customerId: customerId])
        }

        return customerDataRestriction
    }

    public Boolean hasCustomerDataRestriction(Customer customer) {
        String cpfCnpj = customer.cpfCnpj
        return hasDataInCustomerDataRestriction(CustomerDataRestrictionType.CPF_CNPJ, cpfCnpj)
    }

    public Boolean hasIpRestriction(String ip) {
        return hasDataInCustomerDataRestriction(CustomerDataRestrictionType.IP, ip)
    }

    public Boolean hasDataInCustomerDataRestriction(CustomerDataRestrictionType type, String value) {
        return CustomerDataRestriction.query([exists: true, value: value, type: type]).get().asBoolean()
    }

    private Map parseParams(Map params) {
        Map parsedParams = [value: params.value,
                            type: CustomerDataRestrictionType.convert(params.type),
                            isAsaasCustomer: Utils.toBoolean(params.isAsaasCustomer),
                            fraudType: PixAccountConfirmedFraudType.convert(params.fraudType),
                            pixKey: params.pixKey,
                            customerId: null]

        if (parsedParams.type && parsedParams.type.isCpfCnpj()) parsedParams.value = Utils.removeNonNumeric(params.value)
        if (parsedParams.isAsaasCustomer) parsedParams.customerId = Customer.query([column: "id", cpfCnpj: params.value]).get()

        return parsedParams
    }

    private CustomerDataRestriction validateSave(Map params) {
        CustomerDataRestriction validatedCustomerDataRestriction = new CustomerDataRestriction()

        if (!params.type) {
            return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.type"))
        }

        if (Utils.isEmptyOrNull(params.value)) {
            return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.valueIsEmptyOrNull"))
        }

        if (!params.type.hasValidValue(params.value)) {
            return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.invalidValue"))
        }

        if (hasDataInCustomerDataRestriction(params.type, params.value)) {
            return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.valueAlreadyInUse"))
        }

        if (params.isAsaasCustomer) {
            if (!params.type.isCpfCnpj()) return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.pixAccountConfirmedFraud.invalidType"))
            if (!params.fraudType) return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.pixAccountConfirmedFraud.invalidFraudType"))
            if (!params.customerId) return DomainUtils.addError(validatedCustomerDataRestriction, Utils.getMessageProperty("customerDataRestriction.error.pixAccountConfirmedFraud.invalidCustomer"))
        }

        return validatedCustomerDataRestriction
    }
}
