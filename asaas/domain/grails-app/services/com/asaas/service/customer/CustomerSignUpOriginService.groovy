package com.asaas.service.customer

import com.asaas.api.ApiMobileUtils
import com.asaas.customer.CustomerSignUpOriginChannel
import com.asaas.customer.CustomerSignUpOriginPlatform
import com.asaas.customersignuporigin.adapter.CustomerSignUpOriginAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerSignUpOrigin
import com.asaas.log.AsaasLogger
import com.asaas.mobileappidentifier.MobileAppIdentifier
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class CustomerSignUpOriginService {

    public CustomerSignUpOrigin save(CustomerSignUpOriginAdapter customerSignUpOriginAdapter) {
        CustomerSignUpOrigin customerSignUpOrigin = new CustomerSignUpOrigin()
        try {
            CustomerSignUpOrigin validatedCustomerSignUpOrigin = validateSave(customerSignUpOriginAdapter)

            if (validatedCustomerSignUpOrigin.hasErrors()) {
                AsaasLogger.error("CustomerSignUpOriginService - Erro de validação ao salvar origem do customer (${customerSignUpOriginAdapter.customer.id}) com parametros (${customerSignUpOriginAdapter.properties})")
                return validatedCustomerSignUpOrigin
            }

            customerSignUpOrigin.customer = customerSignUpOriginAdapter.customer
            customerSignUpOrigin.originPlatform = customerSignUpOriginAdapter.signUpOrigin.originPlatform
            customerSignUpOrigin.originChannel = customerSignUpOriginAdapter.signUpOrigin.originChannel
            customerSignUpOrigin.appVersion = customerSignUpOriginAdapter.signUpOrigin.appVersion
            customerSignUpOrigin.save(failOnError: true, flush: true)

            return customerSignUpOrigin
        } catch (Exception e) {
            AsaasLogger.error("CustomerSignUpOriginService - Erro ao salvar origem do customer (${customerSignUpOriginAdapter.customer.id}) com parametros (${customerSignUpOriginAdapter.properties}) - ${e.message}", e)
            return customerSignUpOrigin
        }
    }

    public Map buildApiSignUpOriginMap(Map params) {
        Map signUpOrigin = [:]

        MobileAppIdentifier mobileAppIdentifier = ApiMobileUtils.getMobileAppPlatform()
        if (!mobileAppIdentifier) {
            signUpOrigin.originPlatform = CustomerSignUpOriginPlatform.API
            signUpOrigin.originChannel = CustomerSignUpOriginChannel.API
        } else {
            signUpOrigin.originPlatform = mobileAppIdentifier.isAndroid() ? CustomerSignUpOriginPlatform.ANDROID : CustomerSignUpOriginPlatform.IOS

            if (ApiMobileUtils.getApplicationType().isAsaas()) {
                signUpOrigin.originChannel = mobileAppIdentifier.isAndroid() ? CustomerSignUpOriginChannel.ANDROID : CustomerSignUpOriginChannel.IOS
            } else {
                signUpOrigin.originChannel = CustomerSignUpOriginChannel.ASAAS_MONEY
            }

            signUpOrigin.appVersion = ApiMobileUtils.getAppVersion()
        }

        return signUpOrigin
    }

    private CustomerSignUpOrigin validateSave(CustomerSignUpOriginAdapter customerSignUpOriginAdapter) {
        CustomerSignUpOrigin customerSignUpOrigin = new CustomerSignUpOrigin()

        if (!customerSignUpOriginAdapter.signUpOrigin.originPlatform) DomainUtils.addFieldError(customerSignUpOrigin, "originPlatform", "required")

        if (!customerSignUpOriginAdapter.signUpOrigin.originChannel) DomainUtils.addFieldError(customerSignUpOrigin, "originChannel", "required")

        return customerSignUpOrigin
    }
}
