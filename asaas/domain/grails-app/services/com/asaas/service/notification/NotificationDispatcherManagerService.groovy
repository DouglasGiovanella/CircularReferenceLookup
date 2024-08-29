package com.asaas.service.notification

import com.asaas.annotation.CircuitBreaker
import com.asaas.circuitbreakerregistry.NotificationDispatcherCircuitBreaker
import com.asaas.exception.BusinessException
import com.asaas.integration.notificationdispatcher.adapter.PagedNotificationDispatcherPaymentNotificationHistoryAdapter
import com.asaas.integration.notificationdispatcher.dto.NotificationDispatcherPaymentNotificationHistoryRequestDTO
import com.asaas.integration.notificationdispatcher.dto.NotificationDispatcherPaymentNotificationHistoryResponseDTO
import com.asaas.integration.notificationdispatcher.dto.commons.PagedResponseDTO
import com.asaas.integration.notificationdispatcher.NotificationDispatcherManager
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.dto.NotificationDispatcherExternalTemplateAdapter
import com.asaas.notification.dispatcher.dto.NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO
import com.asaas.notification.dispatcher.dto.NotificationDispatcherUpsertCustomTemplateRequestDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import com.google.gson.reflect.TypeToken
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse

import grails.converters.JSON
import grails.transaction.Transactional
import java.lang.reflect.Type

@Transactional
class NotificationDispatcherManagerService {

    public List<Long> sendPaymentNotificationOutboxMessages(List<Map> outboxList) {
        if (outboxList.isEmpty()) return []

        List<SendMessageBatchRequestEntry> messagesList = []
        for (Map outboxItem : outboxList) {
            String messageId = outboxItem.id.toString()
            messagesList.add(
                SendMessageBatchRequestEntry
                    .builder()
                    .id(messageId)
                    .messageDeduplicationId(messageId)
                    .messageGroupId(outboxItem.customerAccountId.toString())
                    .messageBody(outboxItem.payload)
                    .messageAttributes([
                        "eventName": MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(outboxItem.eventName?.toString())
                            .build()
                    ])
                    .build()
            )
        }

        SqsManager sqsManager = new SqsManager("notificationDispatcherPayment")
        List<SendMessageBatchResponse> batchesResponseList = sqsManager.sendMessagesListInBatches(messagesList)

        List<Long> successfulMessagesIdList = batchesResponseList*.successful().flatten { Long.valueOf(it.id()) }
        return successfulMessagesIdList
    }

    public List<Long> sendCustomerOutboxMessages(List<Map> outboxList) {
        if (outboxList.isEmpty()) return []

        List<SendMessageBatchRequestEntry> messagesList = []
        for (Map outboxItem : outboxList) {
            String messageId = outboxItem.id.toString()
            messagesList.add(
                SendMessageBatchRequestEntry
                    .builder()
                    .id(messageId)
                    .messageDeduplicationId(messageId)
                    .messageGroupId(outboxItem.customerId.toString())
                    .messageBody(outboxItem.payload)
                    .messageAttributes([
                        "eventName": MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(outboxItem.eventName.toString())
                            .build()
                    ])
                    .build()
            )
        }

        SqsManager sqsManager = new SqsManager("notificationDispatcherCustomer")
        List<SendMessageBatchResponse> batchesResponseList = sqsManager.sendMessagesListInBatches(messagesList)

        List<Long> successfulMessagesIdList = batchesResponseList*.successful().flatten { Long.valueOf(it.id()) }
        return successfulMessagesIdList
    }

    @CircuitBreaker(name = NotificationDispatcherCircuitBreaker.HISTORY, fallbackMethodName = "listPaymentNotificationHistoryFallback")
    public PagedNotificationDispatcherPaymentNotificationHistoryAdapter listPaymentNotificationHistory(NotificationDispatcherPaymentNotificationHistoryRequestDTO notificationHistoryRequestDTO) {
        NotificationDispatcherManager notificationDispatcherManager = new NotificationDispatcherManager()
        notificationDispatcherManager.get("/paymentNotificationHistory/list", notificationHistoryRequestDTO.properties)
        Type pagedResponseDTOType = TypeToken.getParameterized(PagedResponseDTO, NotificationDispatcherPaymentNotificationHistoryResponseDTO).getType()

        if (notificationDispatcherManager.isSuccessful()) {
            PagedResponseDTO<NotificationDispatcherPaymentNotificationHistoryResponseDTO> pagedResponseDTO = GsonBuilderUtils.buildClassFromJson((notificationDispatcherManager.responseBody as JSON).toString(), pagedResponseDTOType)
            return new PagedNotificationDispatcherPaymentNotificationHistoryAdapter(pagedResponseDTO)
        }
        if (notificationDispatcherManager.isBadRequest()) throw new BusinessException(notificationDispatcherManager.getErrorMessage())
        if (notificationDispatcherManager.isTimeout()) {
            AsaasLogger.error("NotificationDispatcherManagerService.listPaymentNotificationHistory() -> Timeout ao listar histórico de notificações")
        } else {
            AsaasLogger.error("NotificationDispatcherManagerService.listPaymentNotificationHistory() -> Erro ao listar histórico de notificações [error: ${notificationDispatcherManager.responseBody}, status: ${notificationDispatcherManager.statusCode}]")
        }

        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public PagedNotificationDispatcherPaymentNotificationHistoryAdapter listPaymentNotificationHistoryFallback(NotificationDispatcherPaymentNotificationHistoryRequestDTO notificationHistoryRequestDTO, Throwable throwable) {
        return new PagedNotificationDispatcherPaymentNotificationHistoryAdapter(new PagedResponseDTO<NotificationDispatcherPaymentNotificationHistoryResponseDTO>())
    }

    @CircuitBreaker(name = NotificationDispatcherCircuitBreaker.FULLY_INTEGRATED, fallbackMethodName = "requestToggleEnableCustomerIntegrationListFallback")
    public Boolean requestEnableCustomerIntegrationList(NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO) {
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.logged = false
        manager.returnAsList = false
        manager.post("/customerIntegration/enable", requestDTO.properties)
        if (!manager.isSuccessful()) {
            throw new RuntimeException("Erro ao habilitar clientes para o Notification Dispatcher [${requestDTO.customerIdList}]")
        }
        return true
    }

    @CircuitBreaker(name = NotificationDispatcherCircuitBreaker.FULLY_INTEGRATED, fallbackMethodName = "requestToggleEnableCustomerIntegrationListFallback")
    public Boolean requestDisableCustomerIntegrationList(NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO) {
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.logged = false
        manager.returnAsList = false
        manager.post("/customerIntegration/disable", requestDTO.properties)
        if (!manager.isSuccessful()) {
            throw new RuntimeException("Erro ao desabilitar clientes para o Notification Dispatcher [${requestDTO.customerIdList}]")
        }
        return true
    }

    @SuppressWarnings("UnusedMethodParameter")
    public Boolean requestToggleEnableCustomerIntegrationListFallback(NotificationDispatcherPublishCustomerIntegratedToggleEnabledDTO requestDTO, Throwable throwable) {
        return false
    }

    @CircuitBreaker(name = NotificationDispatcherCircuitBreaker.UPSERT_CUSTOM_TEMPLATE)
    public NotificationDispatcherExternalTemplateAdapter upsertCustomTemplate(NotificationDispatcherUpsertCustomTemplateRequestDTO requestDTO) {
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.logged = false
        manager.returnAsList = false

        manager.post("/customTemplates", requestDTO.properties)

        if (!manager.isSuccessful()) {
            throw new RuntimeException("Erro requisitar criação / atualização de template personalizado para o Notification Dispatcher")
        }

        String responseBody = (manager.responseBody as JSON).toString()
        return GsonBuilderUtils.buildClassFromJson(responseBody, NotificationDispatcherExternalTemplateAdapter)
    }
}
