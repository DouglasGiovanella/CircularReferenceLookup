package com.asaas.service.com.asaas.service.customer

import com.asaas.customerlogo.NotificationLogo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.io.FilenameUtils
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CustomerNotificationConfigService {

    final static String ASAAS_SPAM_SUBUSER_USERNAME = "asaas.spam"

    def crypterService
    def customerMailService
    def grailsApplication
    def notificationDispatcherCustomerOutboxService

    public void saveCustomerLogo(Customer customer, CommonsMultipartFile logoFile) {
        NotificationLogo notificationLogo = new NotificationLogo(customer)

        File temporaryDiskFile = File.createTempFile(logoFile.originalFilename ?: "temp", ".tmp")
        logoFile.transferTo(temporaryDiskFile)

        notificationLogo.writeFile(temporaryDiskFile, FilenameUtils.getExtension(logoFile.originalFilename))

        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.findOrCreateByCustomer(customer)
        customerNotificationConfig.logoName = "${customer.id}.${FilenameUtils.getExtension(logoFile.originalFilename)}"
        customerNotificationConfig.enabled = true

        customerNotificationConfig.save()
        notificationDispatcherCustomerOutboxService.onCustomerNotificationConfigUpdated(customerNotificationConfig)
    }

    public void saveCustomerSmsFrom(Customer customer, String smsFrom) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.findOrCreateByCustomer(customer)
        customerNotificationConfig.smsFrom = smsFrom
        customerNotificationConfig.enabled = true

        customerNotificationConfig.save(failOnError: true)
        notificationDispatcherCustomerOutboxService.onCustomerNotificationConfigUpdated(customerNotificationConfig)
    }

    public Boolean isCustomerEmailProviderApiKeyCreated(Customer customer) {
        return CustomerNotificationConfig.findIfEnabled(customer.id, [exists: true, "encryptedEmailProviderApiKey[isNotNull]": true]).get().asBoolean()
    }

    public Map getCustomerEmailProviderCredentials(Customer customer) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.query(customerId: customer.id).get()

        if (!customerNotificationConfig?.emailProviderUsername) {
            return [
                username: grailsApplication.config.grails.mail.sendgrid.username,
                password: grailsApplication.config.grails.mail.sendgrid.password
            ]
        }

        return [
            username: customerNotificationConfig.emailProviderUsername,
            password: crypterService.decryptDomainProperty(customerNotificationConfig, "emailProviderPassword", customerNotificationConfig.emailProviderPassword)
        ]
    }

    public CustomerNotificationConfig saveCustomerEmailProviderCredentials(Customer customer, String sendGridUsername) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.findOrCreateByCustomer(customer)

        if (customerNotificationConfig.emailProviderUsername &&
            customerNotificationConfig.emailProviderUsername != ASAAS_SPAM_SUBUSER_USERNAME) {
            throw new RuntimeException("Já existe uma subconta configurada para o Customer [${customer.id}]")
        }

        customerNotificationConfig.emailProviderUsername = sendGridUsername

        String generatedPassword = UserUtils.generateRandomPassword()
        customerNotificationConfig.emailProviderPassword = crypterService.encryptDomainProperty(customerNotificationConfig, "emailProviderPassword", generatedPassword)

        customerNotificationConfig.save(failOnError: true)

        return customerNotificationConfig
    }

    public CustomerNotificationConfig saveCustomerEmailProviderApiKey(Customer customer, String apiKey) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.findOrCreateByCustomer(customer)

        if (customerNotificationConfig.encryptedEmailProviderApiKey) {
            throw new RuntimeException("Já existe uma ApiKey configurada para o Customer [${customer.id}]")
        }

        customerNotificationConfig.encryptedEmailProviderApiKey = crypterService.encryptDomainProperty(customerNotificationConfig, "encryptedEmailProviderApiKey", apiKey)
        customerNotificationConfig.save(failOnError: true)

        return customerNotificationConfig
    }

    public CustomerNotificationConfig createCustomerNotificationConfigIfNotExists(Customer customer) {
        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.findOrCreateByCustomer(customer)
        customerNotificationConfig.enabled = true

        customerNotificationConfig.save(failOnError: true)
        notificationDispatcherCustomerOutboxService.onCustomerNotificationConfigUpdated(customerNotificationConfig)

        return customerNotificationConfig
    }

    public List<Long> setCustomerListToAsaasSpamSendgridSubUser(List<Long> customerIdList) {
        Customer customerWithAsaasSpamSubUser = CustomerNotificationConfig.query([
            column: "customer",
            ignoreCustomer: true,
            emailProviderUsername: ASAAS_SPAM_SUBUSER_USERNAME,
            "encryptedEmailProviderApiKey[isNotNull]": true,
            enabled: true,
            limit: 1
        ]).get() as Customer

        if (!customerWithAsaasSpamSubUser) {
            throw new RuntimeException("Não foi possível encontrar nenhum CustomerNotificationConfig para SubUser [${ ASAAS_SPAM_SUBUSER_USERNAME }]")
        }

        String decryptedApiKey = customerMailService.getEmailApiKey(customerWithAsaasSpamSubUser)
        return updateCustomerListSubUserConfig(customerIdList, true, decryptedApiKey)
    }

    public List<Long> setCustomerListToAsaasSendgridSubUser(List<Long> customerIdList) {
        return updateCustomerListSubUserConfig(customerIdList, false, null)
    }

    private updateCustomerListSubUserConfig(List<Long> customerIdList, Boolean migrateToAsaasSpamSubUser, String apiKey) {
        List<Long> customerWithErrorIdList = []
        for (Long customerId : customerIdList) {
            Boolean updatedSuccess = updateCustomerSubUserConfig(customerId, migrateToAsaasSpamSubUser, apiKey)
            if (updatedSuccess) continue

            customerWithErrorIdList.add(customerId)
        }

        return customerWithErrorIdList
    }

    private Boolean updateCustomerSubUserConfig(Long customerId, Boolean migrateToAsaasSpamSubUser, String apiKey) {
        Boolean hasErrors = false
        Utils.withNewTransactionAndRollbackOnError({
            CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.query([customerId: customerId]).get()
            if (!customerNotificationConfig) {
                customerNotificationConfig = new CustomerNotificationConfig()
                customerNotificationConfig.customer = Customer.get(customerId)
                customerNotificationConfig.enabled = true
                customerNotificationConfig.save(failOnError: true)
            }

            String ableToUpdateUsername = migrateToAsaasSpamSubUser ? null : ASAAS_SPAM_SUBUSER_USERNAME
            if (customerNotificationConfig.emailProviderUsername != ableToUpdateUsername) {
                throw new RuntimeException("O Customer [${ customerId }] tem um SubUser diferente do esperado: ${ customerNotificationConfig.emailProviderUsername }")
            }

            customerNotificationConfig.encryptedEmailProviderApiKey = null
            if (apiKey) {
                customerNotificationConfig.encryptedEmailProviderApiKey = crypterService.encryptDomainProperty(customerNotificationConfig, "encryptedEmailProviderApiKey", apiKey)
            }

            String newUsername = migrateToAsaasSpamSubUser ? ASAAS_SPAM_SUBUSER_USERNAME : null
            customerNotificationConfig.emailProviderUsername = newUsername
            customerNotificationConfig.save(failOnError: true)

            notificationDispatcherCustomerOutboxService.onCustomerNotificationConfigUpdated(customerNotificationConfig)
        }, [
            logErrorMessage: "CustomerNotificationConfigService.updateCustomerSubUserConfig >> Falha ao atualizar o Customer [${ customerId }]",
            onError: { hasErrors = true }
        ])

        return !hasErrors
    }
}
