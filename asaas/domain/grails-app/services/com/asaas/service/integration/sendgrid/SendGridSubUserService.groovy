package com.asaas.service.integration.sendgrid

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class SendGridSubUserService {

    def customerNotificationConfigService
    def sendGridManagerService

    public void createSendGridSubUserIfNecessary(Customer customer) {
        if (customerNotificationConfigService.isCustomerEmailProviderApiKeyCreated(customer)) return

        try {
            String customerSendGridSubUserUsername = "CUSTOMER_${ customer.id }_CUSTOM_NOTIFICATION"
            customerNotificationConfigService.saveCustomerEmailProviderCredentials(customer, customerSendGridSubUserUsername)

            List<String> ipList = sendGridManagerService.getIpList(customer).collect( { it.ip } )
            sendGridManagerService.createSubUser(customer, ipList)

            String authenticatedDomain = sendGridManagerService.getDomainList(customer).first().id
            sendGridManagerService.associateDomain(customer, authenticatedDomain)

            String linkBranding = sendGridManagerService.getDefaultLinkBranding(customer).id
            sendGridManagerService.associateLinkBranding(customer, linkBranding)

            String customerSendGridSubUserApiKeyName = "CUSTOMER_${ customer.id }_SUBUSER_API_KEY"
            Map apiKeyData = sendGridManagerService.createApiKey(customer, customerSendGridSubUserApiKeyName)
            customerNotificationConfigService.saveCustomerEmailProviderApiKey(customer, apiKeyData.api_key)

            validateSubUserCountLimit()
        } catch (Exception exception) {
            AsaasLogger.error("SendGridSubUserService.createSendGridSubUserIfNecessary >> Falha no cadastro de novo subuser para customer [${ customer.id }]", exception)
            throw new RuntimeException(exception.message)
        }
    }

    private void validateSubUserCountLimit() {
        final Integer subUserCountLimit = 10_000

        Integer subUserCount = CustomerNotificationConfig.query([
            exists: true,
            disableSort: true,
            ignoreCustomer: true,
            "encryptedEmailProviderApiKey[isNotNull]": true
        ]).count()

        if (subUserCount < (subUserCountLimit * 0.9)) return
        AsaasLogger.warn("SendGridSubUserService.validateSubUserCountLimit >> A criação de subcontas já atingiu 90% do limite")
    }
}
