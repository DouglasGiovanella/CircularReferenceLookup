package com.asaas.service.asaaserp

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asaaserp.AsaasErpUserConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasErpCustomerConfigService {

    def crypterService
    def asaasErpCustomerConfigCacheService
    def asaasErpCustomerManagerService
    def asaasErpFinancialTransactionNotificationService
    def asyncActionService
    def asaasErpUserConfigService

    public void save(Customer customer, String externalId, String apiKey) {
        AsaasErpCustomerConfig customerConfig = new AsaasErpCustomerConfig()
        customerConfig.customer = customer
        customerConfig.externalId = externalId
        customerConfig.fullyIntegrated = false

        customerConfig.save(failOnError: true)

        asaasErpCustomerConfigCacheService.evict(customer.id)

        customerConfig.apiKey = crypterService.encryptDomainProperty(customerConfig, "apiKey", apiKey)

        customerConfig.save(failOnError: true)
    }

    public void delete(Long customerId) {
        AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.query([customerId: customerId]).get()
        if (!asaasErpCustomerConfig) return

        asaasErpCustomerConfig.deleted = true
        asaasErpCustomerConfig.save(failOnError: true)

        asaasErpFinancialTransactionNotificationService.deletePendingNotification(asaasErpCustomerConfig.id)

        Map asyncActionData = [asaasErpCustomerConfigId: asaasErpCustomerConfig.id]
        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, AsyncActionType.SEND_DISABLED_CUSTOMER_NOTIFICATION_TO_ASAAS_ERP)) return
        asyncActionService.save(AsyncActionType.SEND_DISABLED_CUSTOMER_NOTIFICATION_TO_ASAAS_ERP, asyncActionData)
    }

    public void sendPendingDisabledCustomerNotificationToAsaasErp() {
        final Integer max = 500
        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.SEND_DISABLED_CUSTOMER_NOTIFICATION_TO_ASAAS_ERP, max)) {
            Utils.withNewTransactionAndRollbackOnError ({
                AsaasErpCustomerConfig asaasErpCustomerConfig = AsaasErpCustomerConfig.read(Utils.toLong(asyncActionData.asaasErpCustomerConfigId))
                List<AsaasErpUserConfig> asaasErpUserConfigList = AsaasErpUserConfig.query([customerId: asaasErpCustomerConfig.customer.id]).list()
                for (AsaasErpUserConfig asaasErpUserConfig : asaasErpUserConfigList) {
                    asaasErpUserConfigService.delete(asaasErpUserConfig)
                }

                asaasErpCustomerManagerService.notifyDisabledCustomer(asaasErpCustomerConfig)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasErpService.sendPendingDisabledCustomerNotificationToAsaasErp >> Erro no cancelamento da integração da conta do cliente [${asyncActionData.asaasErpCustomerConfigId}] para o AsaasERP. ID: [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }
}
