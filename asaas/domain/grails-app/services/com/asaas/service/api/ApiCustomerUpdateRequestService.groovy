package com.asaas.service.api

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiCustomerUpdateRequestParser
import com.asaas.api.ApiProviderParser
import com.asaas.customer.BaseCustomer
import com.asaas.customer.PersonType
import com.asaas.customercommercialinfo.adapter.PeriodicCustomerCommercialInfoUpdateAdapter
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class ApiCustomerUpdateRequestService extends ApiBaseService {

    def apiResponseBuilderService
    def customerCriticalActionService
    def customerUpdateRequestService
    def mandatoryCustomerCommercialInfoService
    def periodicCustomerCommercialInfoUpdateService

    public Map find(Map params) {
        Customer customer = getProviderInstance(params)
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(customer)

        return apiResponseBuilderService.buildSuccess(ApiCustomerUpdateRequestParser.buildResponseItem(customerUpdateRequest ?: customer, [:]))
    }

    public Map save(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiCustomerUpdateRequestParser.parseRequestParams(customer, params)

        CustomerUpdateRequest validatedCustomerUpdateRequest = validateSave(fields)
        if (validatedCustomerUpdateRequest.hasErrors()) return apiResponseBuilderService.buildErrorList(validatedCustomerUpdateRequest)

        BaseCustomer baseCustomer = customerUpdateRequestService.save(customer.id, fields)

        if (baseCustomer.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(baseCustomer)
        }

        return apiResponseBuilderService.buildSuccess(ApiCustomerUpdateRequestParser.buildResponseItem(baseCustomer, [buildCriticalAction: true]))
    }

    public Map updateMandatoryInfo(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiCustomerUpdateRequestParser.parseIncomeParams(params)

        mandatoryCustomerCommercialInfoService.update(customer, fields)

        return apiResponseBuilderService.buildSuccess([:])
    }

    public Map updatePeriodicInfo(Map params) {
        User user = UserUtils.getCurrentUser()

        Map fields = ApiCustomerUpdateRequestParser.parseUpdatePeriodicInfoRequestParams(user.customer, params)
        fields.cpfCnpj = user.customer.cpfCnpj

        PeriodicCustomerCommercialInfoUpdateAdapter periodicCustomerCommercialInfoUpdateAdapter = new PeriodicCustomerCommercialInfoUpdateAdapter(user.customer, fields)

        BaseCustomer baseCustomer = periodicCustomerCommercialInfoUpdateService.save(user, periodicCustomerCommercialInfoUpdateAdapter)

        if (baseCustomer.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(baseCustomer)
        }

        return apiResponseBuilderService.buildSuccess(ApiProviderParser.buildResponseItem(user.customer))
    }

    public Map requestUpdatePeriodicInfoToken() {
        User user = UserUtils.getCurrentUser()

        CriticalActionGroup synchronousGroup = customerCriticalActionService.saveCommercialInfoPeriodicUpdateCriticalActionGroup(user)

        if (synchronousGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(synchronousGroup)
        }

        return ApiCriticalActionParser.buildGroupResponseItem(synchronousGroup)
    }

    private CustomerUpdateRequest validateSave(Map params) {
        CustomerUpdateRequest customerUpdateRequest = new CustomerUpdateRequest()

        if (!params.containsKey("personType")) {
            DomainUtils.addError(customerUpdateRequest, "É necessário informar o tipo de pessoa.")
        }

        if (!params.containsKey("birthDate") && params.personType == PersonType.FISICA) {
            DomainUtils.addError(customerUpdateRequest, "É necessário informar a data de nascimento.")
        }

        if (!params.containsKey("cpfCnpj")) {
            DomainUtils.addError(customerUpdateRequest, "É necessário informar o CPF/CNPJ.")
        }

        if (!params.containsKey("postalCode")) {
            DomainUtils.addError(customerUpdateRequest, "É necessário informar o CEP.")
        }

        return customerUpdateRequest
    }
}
