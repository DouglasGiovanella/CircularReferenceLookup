package com.asaas.service.notification

import com.asaas.crypto.RsaCrypter
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.notification.NotificationDispatcherCustomer
import com.asaas.domain.notification.NotificationDispatcherCustomerOutbox
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.customeroutbox.NotificationDispatcherCustomerOutboxEventName
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherCustomerBaseDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherPublishCustomNotificationTemplateUpdatedEventRequestDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherPublishCustomerInvoiceConfigUpdatedEventRequestDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherPublishCustomerNotificationConfigUpdatedEventRequestDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherPublishCustomerParameterUpdatedEventRequestDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.NotificationDispatcherPublishCustomerUpdatedEventRequestDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.children.CustomerCustomNotificationTemplateDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.children.CustomerDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.children.CustomerInvoiceConfigDTO
import com.asaas.notification.dispatcher.customeroutbox.dto.children.CustomerNotificationConfigDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherCustomerOutboxService {

    def crypterService
    def featureFlagService
    def grailsApplication
    def notificationDispatcherCustomerService
    def notificationDispatcherManagerService

    public void processPendingOutboxEvents() {
        final Integer maxCustomersPerExecution = 500
        final Integer minCustomersForNewThread = 100
        final Integer maxItemsPerThread = 100

        List<Long> outboxCustomerIdList = NotificationDispatcherCustomerOutbox.query([
            distinct: "customerId",
            disableSort: true,
        ]).list(max: maxCustomersPerExecution)
        if (!outboxCustomerIdList) return

        List<Long> idToDeleteList = Collections.synchronizedList(new ArrayList<Long>())
        ThreadUtils.processWithThreadsOnDemand(outboxCustomerIdList, minCustomersForNewThread, { List<Long> customerIdSubList ->
            List<Map> outboxItemList = listItemsFromCustomerIdListWithNewTransaction(customerIdSubList, maxItemsPerThread)
            List<Long> successfulIdList = notificationDispatcherManagerService.sendCustomerOutboxMessages(outboxItemList)
            if (successfulIdList) idToDeleteList.addAll(successfulIdList)
        })

        deleteWithNewTransaction(idToDeleteList)
    }

    public void onCustomerUpdated(Customer customer) {
        if (!shouldSaveEvent(customer)) return

        NotificationDispatcherPublishCustomerUpdatedEventRequestDTO payloadDTO = new NotificationDispatcherPublishCustomerUpdatedEventRequestDTO()
        payloadDTO.customer = new CustomerDTO(customer)

        save(customer.id, NotificationDispatcherCustomerOutboxEventName.CUSTOMER_UPDATED, payloadDTO)
    }

    public void onCustomerInvoiceConfigUpdated(CustomerInvoiceConfig customerInvoiceConfig) {
        if (!shouldSaveEvent(customerInvoiceConfig.customer)) return

        NotificationDispatcherPublishCustomerInvoiceConfigUpdatedEventRequestDTO payloadDTO = new NotificationDispatcherPublishCustomerInvoiceConfigUpdatedEventRequestDTO()
        payloadDTO.customerInvoiceConfig = new CustomerInvoiceConfigDTO(customerInvoiceConfig)

        save(customerInvoiceConfig.customer.id, NotificationDispatcherCustomerOutboxEventName.CUSTOMER_INVOICE_CONFIG_UPDATED, payloadDTO)
    }

    public void onCustomerNotificationConfigUpdated(CustomerNotificationConfig customerNotificationConfig) {
        if (!shouldSaveEvent(customerNotificationConfig.customer)) return

        NotificationDispatcherPublishCustomerNotificationConfigUpdatedEventRequestDTO payloadDTO = new NotificationDispatcherPublishCustomerNotificationConfigUpdatedEventRequestDTO()
        payloadDTO.customerNotificationConfig = new CustomerNotificationConfigDTO(customerNotificationConfig, buildEmailProviderSecrets(customerNotificationConfig))

        save(customerNotificationConfig.customer.id, NotificationDispatcherCustomerOutboxEventName.CUSTOMER_NOTIFICATION_CONFIG_UPDATED, payloadDTO)
    }

    public void onCustomerParameterUpdated(CustomerParameter parameter) {
        if (!shouldSaveEvent(parameter.customer)) return
        if (!shouldSaveCustomerParameter(parameter.name)) return

        NotificationDispatcherPublishCustomerParameterUpdatedEventRequestDTO payload = new NotificationDispatcherPublishCustomerParameterUpdatedEventRequestDTO(parameter)

        save(parameter.customer.id, NotificationDispatcherCustomerOutboxEventName.CUSTOMER_PARAMETER_UPDATED, payload)
    }

    public void onCustomNotificationTemplateUpdated(NotificationTemplate notificationTemplate) {
        if (!shouldSaveEvent(notificationTemplate.provider)) return

        NotificationDispatcherPublishCustomNotificationTemplateUpdatedEventRequestDTO payload = new NotificationDispatcherPublishCustomNotificationTemplateUpdatedEventRequestDTO()
        payload.customerCustomNotificationTemplate = new CustomerCustomNotificationTemplateDTO(notificationTemplate)

        save(notificationTemplate.provider.id, NotificationDispatcherCustomerOutboxEventName.CUSTOMER_CUSTOM_NOTIFICATION_TEMPLATE_UPDATED, payload)
    }

    private void save(Long customerId, NotificationDispatcherCustomerOutboxEventName eventName, NotificationDispatcherCustomerBaseDTO payloadObject) {
        payloadObject.enabled = notificationDispatcherCustomerService.isCustomerEnabled(customerId)

        NotificationDispatcherCustomerOutbox outbox = new NotificationDispatcherCustomerOutbox()
        outbox.customerId = customerId
        outbox.eventName = eventName
        outbox.payload = GsonBuilderUtils.toJsonWithoutNullFields(payloadObject)

        final Integer textLimitSize = 65535
        if (outbox.payload.size() > textLimitSize) {
            AsaasLogger.error("NotificationDispatcherCustomerOutboxService.save >> Payload do outbox ultrapassou o limite de ${textLimitSize} caracteres: ${outbox.payload}")
            return
        }

        outbox.save(failOnError: true)
    }

    private Boolean shouldSaveEvent(Customer customer) {
        if (!featureFlagService.isNotificationDispatcherOutboxEnabled()) return false

        Boolean isMigrated = NotificationDispatcherCustomer.query([exists: true, customerId: customer.id, disableSort: true]).get().asBoolean()
        return isMigrated
    }

    private List<Map> listItemsFromCustomerIdListWithNewTransaction(List<Long> customerIdList, Integer max) {
        List<Map> outboxItemList
        Utils.withNewTransactionAndRollbackOnError({
            outboxItemList = NotificationDispatcherCustomerOutbox.query([
                columnList: ["id", "customerId", "eventName", "payload"],
                "customerId[in]": customerIdList,
                order: "asc"
            ]).list(max: max)
        }, [logErrorMessage: "NotificationDispatcherCustomerOutboxService.listItemsFromCustomerIdListWithNewTransaction >> Erro ao buscar outboxItemList para customerIds ${customerIdList}"])

        return outboxItemList ?: []
    }

    private void deleteWithNewTransaction(List<Long> idToDeleteList) {
        final Integer maxItemsPerOperation = 500

        Utils.withNewTransactionAndRollbackOnError({
            for (List<Long> collatedList : idToDeleteList.collate(maxItemsPerOperation)) {
                NotificationDispatcherCustomerOutbox.where {
                    "in"("id", collatedList)
                }.deleteAll()
            }
        }, [logErrorMessage: "NotificationDispatcherCustomerOutboxService.deleteWithNewTransaction >> Erro ao deletar outbox com ids ${idToDeleteList}"])
    }

    private Boolean shouldSaveCustomerParameter(CustomerParameterName name) {
        if (name.valueType != Boolean) return false

        return CustomerParameterName.listNotificationDispatcherParameters().contains(name)
    }

    private Map buildEmailProviderSecrets(CustomerNotificationConfig customerNotificationConfig) {
        byte[] moduleBytes = grailsApplication.config.notificationDispatcher.outbox.encryption.rsa.public.module.decodeBase64()
        byte[] exponentBytes = grailsApplication.config.notificationDispatcher.outbox.encryption.rsa.public.exponent.decodeBase64()

        Map emailProviderSecretsMap = [:]
        if (customerNotificationConfig.encryptedEmailProviderApiKey) {
            String decryptedApiKey = crypterService.decryptDomainProperty(customerNotificationConfig, "encryptedEmailProviderApiKey", customerNotificationConfig.encryptedEmailProviderApiKey)
            emailProviderSecretsMap.emailProviderApiKeyEncrypted = RsaCrypter.encrypt(decryptedApiKey.getBytes(), moduleBytes, exponentBytes)
        }
        if (customerNotificationConfig.emailProviderPassword) {
            String decryptedPassword = crypterService.decryptDomainProperty(customerNotificationConfig, "emailProviderPassword", customerNotificationConfig.emailProviderPassword)
            emailProviderSecretsMap.emailProviderPasswordEncrypted = RsaCrypter.encrypt(decryptedPassword.getBytes(), moduleBytes, exponentBytes)
        }

        return emailProviderSecretsMap
    }
}
