package com.asaas.service.customer.periodiccustomercommercialinfoupdate

import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.BaseCustomer
import com.asaas.customercommercialinfo.adapter.PeriodicCustomerCommercialInfoUpdateAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.service.customer.CustomerService
import com.asaas.status.Status
import grails.transaction.Transactional

@Transactional
class PeriodicCustomerCommercialInfoUpdateService {

    def crypterService
    def customerCommercialInfoExpirationService
    def customerCriticalActionService
    def customerService
    def customerUpdateRequestService

    public BaseCustomer save(User user, PeriodicCustomerCommercialInfoUpdateAdapter periodicCustomerCommercialInfoUpdateAdapter) {
        Customer customer = user.customer
        BaseCustomer customerToBeUpdated = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer) ?: customer

        Map saveParams = periodicCustomerCommercialInfoUpdateAdapter.buildUpdatableProperties()
        Map validateParams = saveParams + periodicCustomerCommercialInfoUpdateAdapter.buildDefaultInfoMap()
        CustomerUpdateRequest validatedCustomerUpdateRequest = customerUpdateRequestService.validateNamedParams(customer, validateParams)
        if (validatedCustomerUpdateRequest.hasErrors()) return validatedCustomerUpdateRequest

        Customer validatedCustomer = customerService.validateCommercialInfoUpdate(customer.id, validateParams)
        if (validatedCustomer.hasErrors()) return validatedCustomer

        if (!customerUpdateRequestService.hasCustomerUpdateRequestChanges(customer, customerToBeUpdated, saveParams)) {
            AsaasLogger.info("PeriodicCustomerCommercialInfoUpdateService.save >> cliente apenas confirmou os dados comerciais na atualização periódica. Customer [${user.customer.id}]")
            customerCommercialInfoExpirationService.save(customer)
            return customerToBeUpdated
        }

        Long criticalActionGroupId = periodicCustomerCommercialInfoUpdateAdapter.criticalActionGroupId
        if (!criticalActionGroupId) {
            throw new RuntimeException("Tentativa de atualização períodica de dados comerciais com mudanças nos dados e sem evento crítico")
        }

        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.query([provider: customer, status: Status.PENDING]).get() ?: new CustomerUpdateRequest(provider: customer, status: Status.PENDING)
        customerUpdateRequest.properties[CustomerService.COMMERCIAL_INFO_UPDATABLE_PROPERTIES] = customerToBeUpdated.properties[CustomerService.COMMERCIAL_INFO_UPDATABLE_PROPERTIES]

        List<String> periodicUpdatableProperties = periodicCustomerCommercialInfoUpdateAdapter.buildUpdatableProperties().keySet().toList()
        customerUpdateRequest.properties[periodicUpdatableProperties] = saveParams
        customerUpdateRequest.incomeValue = null
        customerUpdateRequest.save(flush: true, failOnError: false)

        if (saveParams.incomeValue) {
            customerUpdateRequest.incomeValue = crypterService.encryptDomainProperty(customerUpdateRequest, "incomeValue", saveParams.incomeValue.toString())
            customerUpdateRequest.save(failOnError: false)
        }

        String criticalActionToken = periodicCustomerCommercialInfoUpdateAdapter.criticalActionToken
        customerCriticalActionService.validateCriticalActionToken(user, criticalActionToken, criticalActionGroupId, CriticalActionType.PERIODIC_CUSTOMER_COMMERCIAL_INFO_UPDATE)

        customerUpdateRequestService.cancelRequestsAwaitingActionAuthorization(user.customer)

        return customerUpdateRequestService.onCriticalActionAuthorization(customerUpdateRequest)
    }
}
