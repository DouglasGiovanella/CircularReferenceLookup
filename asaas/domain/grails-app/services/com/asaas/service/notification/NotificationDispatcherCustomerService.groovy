package com.asaas.service.notification

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerNotificationConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.notification.NotificationDispatcherCustomer
import com.asaas.domain.notification.NotificationDispatcherCustomerAccount
import com.asaas.domain.notification.NotificationTemplate
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerAccountStatus
import com.asaas.notification.dispatcher.NotificationDispatcherCustomerStatus
import com.asaas.notification.dispatcher.cache.NotificationDispatcherCustomerCacheVO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO
import com.asaas.redis.RedissonProxy
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.redisson.api.RBucket

@Transactional
class NotificationDispatcherCustomerService {

    def customerPlanService
    def featureFlagService
    def notificationDispatcherCustomerOutboxService
    def notificationDispatcherCustomerAccountService
    def notificationDispatcherCustomerCacheService
    def notificationDispatcherManagerService

    public void processMigration() {
        Integer maxCustomersInProcessing = getMaxCustomerMigrationRate()
        Integer customersInProcessing = countCustomersInProcessing()
        if (customersInProcessing > maxCustomersInProcessing) return

        List<Long> customerIdList = Customer.query([
            column: "id",
            "customerConfigNotificationDisabled[notExists]": true,
            "notificationDispatcherCustomer[notExists]": true,
        ]).list(max: maxCustomersInProcessing)
        for (Long customerId : customerIdList) {
            migrate(customerId, false)
        }
    }

    public void migrate(Long customerId, Boolean isManual) {
        Customer customer = Customer.read(customerId)

        save(customer, true, isManual)

        notificationDispatcherCustomerOutboxService.onCustomerUpdated(customer)

        CustomerInvoiceConfig invoiceConfig = customer.getInvoiceConfig()
        if (invoiceConfig) notificationDispatcherCustomerOutboxService.onCustomerInvoiceConfigUpdated(invoiceConfig)

        CustomerNotificationConfig customerNotificationConfig = CustomerNotificationConfig.query([customerId: customer.id, readOnly: true]).get()

        if (customerNotificationConfig) notificationDispatcherCustomerOutboxService.onCustomerNotificationConfigUpdated(customerNotificationConfig)

        List<CustomerParameter> customerParameterList = CustomerParameter.query([
            customer: customer,
            "name[in]": CustomerParameterName.listNotificationDispatcherParameters()
        ]).list(readOnly: true)
        for (CustomerParameter customerParameter : customerParameterList) {
            notificationDispatcherCustomerOutboxService.onCustomerParameterUpdated(customerParameter)
        }

        if (customerPlanService.isCustomNotificationTemplatesEnabled(customer)) migrateCustomTemplates(customer)
    }

    public void saveIfNecessary(Customer customer) {
        if (!featureFlagService.isNotificationDispatcherSyncNewCustomerEnabled()) return
        if (NotificationDispatcherCustomer.query([exists: true, customerId: customer.id]).get().asBoolean()) return

        save(customer, false, false)
    }

    public void saveFullyIntegreated(Long customerId) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.query([customerId: customerId]).get()
        notificationDispatcherCustomer.enabled = true
        notificationDispatcherCustomer.status = NotificationDispatcherCustomerStatus.FULLY_INTEGRATED
        notificationDispatcherCustomer.save(failOnError: true)
        notificationDispatcherCustomerCacheService.evictByCustomerId(customerId)
    }

    public void setAsSynchronizing(NotificationDispatcherCustomer notificationDispatcherCustomer) {
        notificationDispatcherCustomerCacheService.evictByCustomerId(notificationDispatcherCustomer.id)
        notificationDispatcherCustomer.status = NotificationDispatcherCustomerStatus.SYNCHRONIZING
        notificationDispatcherCustomer.save(failOnError: true)
    }

    public void setCustomerEnabled(Long customerId, Boolean enabled) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.query([customerId: customerId]).get()
        notificationDispatcherCustomer.enabled = enabled
        notificationDispatcherCustomer.save(failOnError: true)
        notificationDispatcherCustomerCacheService.evictByCustomerId(customerId)
    }

    public Boolean isCustomerEnabled(Long customerId) {
        NotificationDispatcherCustomerCacheVO notificationDispatcherCustomerCacheVO = notificationDispatcherCustomerCacheService.byCustomerId(customerId)
        return notificationDispatcherCustomerCacheVO.enabled
    }

    public void setCustomerManualMigrationAsManualFullyIntegrated(Long customerId) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = NotificationDispatcherCustomer.query([customerId: customerId, manualMigration: true]).get()
        if (!notificationDispatcherCustomer) {
            AsaasLogger.warn("NotificationDispatcherCustomerService.setCustomerManualMigrationAsManualFullyIntegrated >> Cliente inválido para atualização de status PROCESSING_MANUAL_FULLY_INTEGRATED [customerId: ${customerId}]")
            return
        }

        if (!notificationDispatcherCustomer.status.isSynchronizing()) {
            AsaasLogger.warn("NotificationDispatcherCustomerService.setCustomerManualMigrationAsManualFullyIntegrated >> Cliente não finalizou o processo de migração [customerId: ${customerId} status: ${notificationDispatcherCustomer.status}]")
            return
        }

        notificationDispatcherCustomer.status = NotificationDispatcherCustomerStatus.PROCESSING_MANUAL_FULLY_INTEGRATED
        notificationDispatcherCustomer.save()
    }

    public void processManualFullyIntegratedCustomerAccounts() {
        List<Long> notificationDispatcherCustomerIdList = NotificationDispatcherCustomer.query([
            column: "customer.id",
            status: NotificationDispatcherCustomerStatus.PROCESSING_MANUAL_FULLY_INTEGRATED,
            manualMigration: true
        ]).list(max: 5)

        if (!notificationDispatcherCustomerIdList) return

        List<Long> customerIdFullyIntegratedList = []
        for (Long customerId : notificationDispatcherCustomerIdList) {
            List<Long> customerAccountIdList = NotificationDispatcherCustomerAccount.query([
                column: "customerAccount.id",
                customerId: customerId,
                "status[ne]": NotificationDispatcherCustomerAccountStatus.FULLY_INTEGRATED
            ]).list(max: 5000)

            if (!customerAccountIdList) {
                customerIdFullyIntegratedList.add(customerId)
                continue
            }

            final Integer minItemsPerThread = 500
            final Integer batchSize = 100
            ThreadUtils.processWithThreadsOnDemand(customerAccountIdList, minItemsPerThread, { List<Long> customerAccountIdSubList ->
                Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerAccountIdSubList, batchSize, batchSize, { Long customerAccountId ->
                    notificationDispatcherCustomerAccountService.confirmCustomerAccountFullyIntegrated(customerAccountId)
                }, [
                    logErrorMessage: "NotificationDispatcherCustomerService.processManualFullyIntegratedCustomerAccounts >> Falha ao marcar CustomerAccount como FULLY_INTEGRATED",
                    appendBatchToLogErrorMessage: true
                ])
            })
        }
        if (!customerIdFullyIntegratedList) return

        NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO = new NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO(customerIdFullyIntegratedList)
        Boolean sent = notificationDispatcherManagerService.requestEnableCustomerIntegrationList(requestDTO)
        if (!sent) return

        for (Long customerId : customerIdFullyIntegratedList) {
            saveFullyIntegreated(customerId)
        }
    }

    public void setMaxCustomerMigrationRate(Integer maxCustomerRate) {
        RBucket<Integer> maxCustomerMigrationRate = RedissonProxy.instance.getBucket("NotificationDispatcherMaxCustomerMigrationRate:value", Integer)
        maxCustomerMigrationRate.set(maxCustomerRate)
    }

    private Integer getMaxCustomerMigrationRate() {
        RBucket<Integer> maxCustomerMigrationRate = RedissonProxy.instance.getBucket("NotificationDispatcherMaxCustomerMigrationRate:value", Integer)
        if (maxCustomerMigrationRate?.get()) return maxCustomerMigrationRate.get()

        return 1
    }

    private void save(Customer customer, Boolean isMigration, Boolean isManual) {
        NotificationDispatcherCustomer notificationDispatcherCustomer = new NotificationDispatcherCustomer()
        notificationDispatcherCustomer.customer = customer
        notificationDispatcherCustomer.status = isMigration ? NotificationDispatcherCustomerStatus.PROCESSING : NotificationDispatcherCustomerStatus.FULLY_INTEGRATED
        notificationDispatcherCustomer.manualMigration = isManual
        notificationDispatcherCustomer.enabled = !isMigration
        notificationDispatcherCustomer.save(failOnError: true)
    }

    private Integer countCustomersInProcessing() {
        return NotificationDispatcherCustomer.query([
            exists: true,
            status: NotificationDispatcherCustomerStatus.PROCESSING,
            disableSort: true
        ]).count()
    }

    private void migrateCustomTemplates(Customer customer) {
        List<NotificationTemplate> notificationTemplateList = NotificationTemplate.query([
            providerId: customer.id,
            isCustomTemplate: true
        ]).list()

        if (!notificationTemplateList) return

        for (NotificationTemplate notificationTemplate : notificationTemplateList) {
            notificationDispatcherCustomerOutboxService.onCustomNotificationTemplateUpdated(notificationTemplate)
        }
    }
}
