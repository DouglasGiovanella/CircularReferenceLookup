package com.asaas.service.mail

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerNotificationConfig
import grails.transaction.Transactional

@Transactional
class CustomerMailService {

    def crypterService
    def grailsApplication

    public String getEmailApiKey(Customer customer) {
        String decryptedApiKey

        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.query([customerId: customer.id]).get()
        if (customerNotificationConfig?.encryptedEmailProviderApiKey) {
            decryptedApiKey = crypterService.decryptDomainProperty(customerNotificationConfig, "encryptedEmailProviderApiKey", customerNotificationConfig.encryptedEmailProviderApiKey)
        }

        return decryptedApiKey ?: grailsApplication.config.asaas.mail.sendgrid.apiKey
    }
}
