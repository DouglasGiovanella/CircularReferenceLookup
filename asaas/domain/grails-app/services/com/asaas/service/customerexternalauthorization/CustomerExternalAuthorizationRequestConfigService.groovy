package com.asaas.service.customerexternalauthorization

import com.asaas.customer.CustomerParameterName
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigSaveAdapter
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigUpdateAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerExternalAuthorizationRequestConfigService {

    def crypterService
    def grailsApplication

    public CustomerExternalAuthorizationRequestConfig save(CustomerExternalAuthorizationRequestConfigSaveAdapter saveAdapter) {
        CustomerExternalAuthorizationRequestConfig validatedConfig = validateSave(saveAdapter)

        if (validatedConfig.hasErrors()) {
            return validatedConfig
        }

        CustomerExternalAuthorizationRequestConfig config = new CustomerExternalAuthorizationRequestConfig()
        config.customer = saveAdapter.customer
        config.email = saveAdapter.email
        config.url = saveAdapter.url
        config.type = saveAdapter.type
        config.enabled = saveAdapter.enabled
        config.apiVersion = CustomerExternalAuthorizationRequestConfig.DEFAULT_API_VERSION
        config.forceAuthorization = saveAdapter.forceAuthorization
        config.save(flush: true, failOnError: true)

        if (saveAdapter.accessToken) {
            config.accessToken = crypterService.encryptDomainProperty(config, "accessToken", saveAdapter.accessToken, grailsApplication.config.asaas.crypter.customerExternalAuthorization.accessToken.secret)
            config.save(failOnError: true)
        }

        return config
    }

    public CustomerExternalAuthorizationRequestConfig update(CustomerExternalAuthorizationRequestConfig config, CustomerExternalAuthorizationRequestConfigUpdateAdapter updateAdapter) {
        CustomerExternalAuthorizationRequestConfig validatedConfig = validateUpdate(config, updateAdapter)

        if (validatedConfig.hasErrors()) {
            return validatedConfig
        }

        config.email = updateAdapter.email
        config.url = updateAdapter.url
        config.type = updateAdapter.type
        config.enabled = updateAdapter.enabled
        config.forceAuthorization = updateAdapter.forceAuthorization

        if (updateAdapter.accessToken) {
            if (updateAdapter.accessToken != config.getDecryptedAccessToken()) {
                config.accessToken = crypterService.encryptDomainProperty(config, "accessToken", updateAdapter.accessToken, grailsApplication.config.asaas.crypter.customerExternalAuthorization.accessToken.secret)
            }
        } else {
            config.accessToken = null
        }

        config.save(failOnError: true)

        return config
    }

    public void delete(CustomerExternalAuthorizationRequestConfig config) {
        config.deleted = true
        config.save(failOnError: true)
    }

    public Boolean hasPixQrCodeConfigEnabled(Customer customer) {
        return hasConfigEnabled(customer, CustomerExternalAuthorizationRequestConfigType.PIX_QR_CODE)
    }

    public Boolean hasTransferConfigEnabled(Customer customer) {
        return hasConfigEnabled(customer, CustomerExternalAuthorizationRequestConfigType.TRANSFER)
    }

    public Boolean hasConfigEnabled(Customer customer, CustomerExternalAuthorizationRequestConfigType configType) {
        if (isAccountOwnerCustomerExternalAuthorizationOnlyAsTemplateEnabled(customer)) return false

        return CustomerExternalAuthorizationRequestConfig.query([customer: customer, type: configType, exists: true, enabled: true]).get().asBoolean()
    }

    public Boolean hasConfigEnabledAndForceUse(Customer customer, CustomerExternalAuthorizationRequestConfigType configType) {
        if (isAccountOwnerCustomerExternalAuthorizationOnlyAsTemplateEnabled(customer)) return false

        return CustomerExternalAuthorizationRequestConfig.query([customer: customer, type: configType, exists: true, enabled: true, forceAuthorization: true]).get().asBoolean()
    }

    private CustomerExternalAuthorizationRequestConfig validateSave(CustomerExternalAuthorizationRequestConfigSaveAdapter saveAdapter) {
        CustomerExternalAuthorizationRequestConfig validatedConfig = new CustomerExternalAuthorizationRequestConfig()

        if (!saveAdapter.type) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.type.validate.error.invalid"))
        }

        if (!saveAdapter.url) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.url.validate.error.notNull"))
        } else if (!saveAdapter.url.startsWith("https://")) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.url.validate.error.invalid"))
        }

        if (!saveAdapter.email) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.email.validate.error.notNull"))
        } else if (!Utils.emailIsValid(saveAdapter.email)) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.email.validate.error.invalid"))
        }

        if (saveAdapter.enabled == null) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.enabled.validate.error.notNull"))
        }

        if (saveAdapter.accessToken) {
            if (saveAdapter.accessToken.length() > CustomerExternalAuthorizationRequestConfig.ACCESS_TOKEN_MAX_LENGTH) {
                DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.accessToken.validate.error.invalid", [CustomerExternalAuthorizationRequestConfig.ACCESS_TOKEN_MAX_LENGTH]))
            }
        }

        return validatedConfig
    }

    private CustomerExternalAuthorizationRequestConfig validateUpdate(CustomerExternalAuthorizationRequestConfig validatedConfig, CustomerExternalAuthorizationRequestConfigUpdateAdapter updateAdapter) {
        if (updateAdapter.forceAuthorization == null) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.forceAuthorization.validate.error.notNull"))
        }

        if (!updateAdapter.type) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.type.validate.error.invalid"))
        }

        if (!updateAdapter.url) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.url.validate.error.notNull"))
        } else if (!updateAdapter.url.startsWith("https://")) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.url.validate.error.invalid"))
        }

        if (!updateAdapter.email) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.email.validate.error.notNull"))
        } else if (!Utils.emailIsValid(updateAdapter.email)) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.email.validate.error.invalid"))
        }

        if (updateAdapter.enabled == null) {
            DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.enabled.validate.error.notNull"))
        }

        if (updateAdapter.accessToken) {
            if (updateAdapter.accessToken.length() > CustomerExternalAuthorizationRequestConfig.ACCESS_TOKEN_MAX_LENGTH) {
                DomainUtils.addError(validatedConfig, Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.accessToken.validate.error.invalid", [CustomerExternalAuthorizationRequestConfig.ACCESS_TOKEN_MAX_LENGTH]))
            }
        }

        return validatedConfig
    }

    private Boolean isAccountOwnerCustomerExternalAuthorizationOnlyAsTemplateEnabled(Customer customer) {
        if (customer.accountOwner) return false

        return CustomerParameter.getValue(customer, CustomerParameterName.ACCOUNT_OWNER_CUSTOMER_EXTERNAL_AUTHORIZATION_ONLY_AS_TEMPLATE).asBoolean()
    }
}
