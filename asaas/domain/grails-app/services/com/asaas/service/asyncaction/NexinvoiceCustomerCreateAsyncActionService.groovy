package com.asaas.service.asyncaction

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.asyncAction.NexinvoiceCustomerCreateAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigApplication
import com.asaas.domain.pushnotification.PushNotificationSendType
import com.asaas.domain.pushnotification.PushNotificationType
import com.asaas.domain.user.User
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceCreateCustomerResponseDTO
import com.asaas.integration.nexinvoice.dto.customer.NexinvoiceCreateUsersResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.nexinvoice.adapter.NexinvoiceCustomerCreateAdapter
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class NexinvoiceCustomerCreateAsyncActionService {

    def nexinvoiceCustomerManagerService
    def baseAsyncActionService
    def nexinvoiceCustomerConfigService
    def customerAlertNotificationService
    def nexinvoiceCustomerConfigCacheService
    def nexinvoiceUserConfigService
    def userService
    def pushNotificationConfigService

    public void save(Customer customer, List<User> userList) {
        final Map asyncActionData = [
            customerId: customer.id,
            userIdList: userList.collect { it.id }
        ]

        baseAsyncActionService.save(new NexinvoiceCustomerCreateAsyncAction(), asyncActionData)
    }

    public Boolean process() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(NexinvoiceCustomerCreateAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return false

        baseAsyncActionService.processListWithNewTransaction(NexinvoiceCustomerCreateAsyncAction, asyncActionDataList, { Map asyncActionData ->
            Customer customer = Customer.read(Utils.toLong(asyncActionData.customerId))

            List<User> userList = asyncActionData.userIdList.collect { it -> User.read(Utils.toLong(it)) }
            String nexinvoiceCustomerConfigPublicId = nexinvoiceCustomerConfigCacheService.byCustomerId(customer.id).publicId

            NexinvoiceCustomerCreateAdapter nexinvoiceCustomerCreateAdapter = new NexinvoiceCustomerCreateAdapter(customer, userList, nexinvoiceCustomerConfigPublicId)
            NexinvoiceCreateCustomerResponseDTO response = nexinvoiceCustomerManagerService.createCustomer(nexinvoiceCustomerCreateAdapter)
            nexinvoiceCustomerConfigService.update(customer, response.idCliente, true)

            for (NexinvoiceCreateUsersResponseDTO userResponse : response.usuarios) {
                User user = User.read(userResponse.idUsuarioAsaas)
                nexinvoiceUserConfigService.update(user, userResponse.idUsuarioNexinvoice, true)
            }

            enableNexinvoiceIntegrationWebhook(customer, nexinvoiceCustomerConfigPublicId)
            customerAlertNotificationService.notifyNexinvoiceIntegrated(customer)
        }, [
            logErrorMessage: "NexinvoiceCustomerCreateAsyncActionService.process >> Não foi possível processar a ação assíncrona [${asyncActionDataList}]",
        ])

        return true
    }

    private void enableNexinvoiceIntegrationWebhook(Customer customer, String nexinvoiceCustomerConfigPublicId) {
        Map params = [
            type: PushNotificationType.BILL,
            application: PushNotificationConfigApplication.NEXINVOICE,
            enabled: true,
            url: "${AsaasApplicationHolder.getConfig().nexinvoice.webhook.url}/${nexinvoiceCustomerConfigPublicId}",
            provider: customer,
            accessToken: AsaasApplicationHolder.getConfig().nexinvoice.webhook.accessToken,
            poolInterrupted: false,
            email: AsaasApplicationHolder.getConfig().nexinvoice.webhook.email,
            events: PushNotificationRequestEvent.listBillEvents(),
            sendType: PushNotificationSendType.SEQUENTIALLY
        ]

        PushNotificationConfig pushNotificationConfig = pushNotificationConfigService.save(customer, params)
        if (pushNotificationConfig.hasErrors()) {
            AsaasLogger.error("NexinvoiceCustomerCreateAsyncActionService.enableNexinvoiceIntegrationWebhook >> Erro ao salvar configuração de webhook para integração com Nexinvoice [${pushNotificationConfig.errors}]")
        }
    }
}
