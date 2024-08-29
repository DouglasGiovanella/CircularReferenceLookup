package com.asaas.service.asaaserp

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.adapter.CreateErpCustomerAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.exception.BusinessException
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional

@Transactional
class AsaasErpIntegrationService {

    def createCustomerService
    def customerParameterService

    public Customer createCustomer(CreateErpCustomerAdapter customerAdapter) {
        def customer = createCustomerService.save(customerAdapter)
        if (customer.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(customer))

        CustomerParameter customerParameter = customerParameterService.save(customer, CustomerParameterName.ALLOW_DUPLICATE_CPF_CNPJ, true)
        if (customerParameter.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(customerParameter))

        return customer
    }
}
