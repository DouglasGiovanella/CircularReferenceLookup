package com.asaas.service.onboardingbradesco

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.user.adapter.UserAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BradescoExternalOnboardingService {

    def createCampaignEventMessageService
    def customerUpdateRequestService
    def userService

    public CustomerUpdateRequest saveCustomerContact(Customer customer, Long userId, Map params) {
        CustomerUpdateRequest customerUpdateRequest = validateCustomerContactParams(params)
        if (customerUpdateRequest.hasErrors()) return customerUpdateRequest

        UserAdapter userAdapter = new UserAdapter()
        userAdapter.id = userId
        userAdapter.customer = customer
        userAdapter.username = params.email
        userAdapter.mobilePhone = PhoneNumberUtils.sanitizeNumber(params.mobilePhone)

        userService.update(userAdapter)

        def validatedCustomerUpdateRequest = customerUpdateRequestService.save(customer.id, params)

        if (validatedCustomerUpdateRequest.hasErrors()) {
            for (error in validatedCustomerUpdateRequest.errors.allErrors) {
                DomainUtils.addError(customerUpdateRequest, error.defaultMessage)
            }
        } else {
            createCampaignEventMessageService.saveForOnboardingFinished(customer)
        }

        return customerUpdateRequest
    }

    private CustomerUpdateRequest validateCustomerContactParams(Map parsedParams) {
        CustomerUpdateRequest validatedCustomerUpdateRequest = new CustomerUpdateRequest()

        if (!Utils.emailIsValid(parsedParams.email)) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar um email válido.")
            return validatedCustomerUpdateRequest
        }

        if (!PhoneNumberUtils.validateMobilePhone(parsedParams.mobilePhone)) {
            DomainUtils.addError(validatedCustomerUpdateRequest, "É necessário informar um número de telefone celular válido.")
            return validatedCustomerUpdateRequest
        }

        return validatedCustomerUpdateRequest
    }
}
