package com.asaas.service.asaaserp

import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.exception.BusinessException
import com.asaas.integration.asaaserp.adapter.customeraccount.AsaasErpCustomerAccountAdapter
import com.asaas.integration.asaaserp.api.AsaasErpManager
import com.asaas.integration.asaaserp.dto.customeraccount.CustomerAccountRequestDTO
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse

@Transactional
class AsaasErpCustomerAccountManagerService {

    public void sendSqsMessageList(List<AsaasErpCustomerAccountAdapter> asaasErpCustomerAccountAdapterList) {
        List<SendMessageBatchRequestEntry> messagesList = []
        for (AsaasErpCustomerAccountAdapter asaasErpCustomerAccountAdapter : asaasErpCustomerAccountAdapterList) {
            CustomerAccountRequestDTO customerAccountRequestDTO = new CustomerAccountRequestDTO(asaasErpCustomerAccountAdapter)
            String messageBody = GsonBuilderUtils.toJsonWithoutNullFields(customerAccountRequestDTO)
            String messageId = "${asaasErpCustomerAccountAdapter.customerAccountId}_${UUID.randomUUID()}"
            String messageGroupId = customerAccountRequestDTO.externalId ?: customerAccountRequestDTO.idIntegrationUser

            messagesList.add(
                SendMessageBatchRequestEntry
                    .builder()
                    .id(messageId)
                    .messageDeduplicationId(messageId)
                    .messageGroupId(messageGroupId)
                    .messageBody(messageBody)
                    .build()
            )
        }

        final String SQS_QUEUE_CONFIG_KEY = "asaasErpCustomerAccountIntegration"
        final String SQS_ACCESS_CONFIG = "asaasErpCustomerAccountIntegration"

        try {
            SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY, SQS_ACCESS_CONFIG)
            List<SendMessageBatchResponse> batchesResponseList = sqsManager.sendMessagesListInBatches(messagesList)

            SendMessageBatchResponse failedResponse = batchesResponseList.find { it.hasFailed() }
            if (!failedResponse) return

            AsaasLogger.error("AsaasErpCustomerAccountManagerService.sendSqsMessageList -> Erro ao enviar mensagens em lote para fila SQS. Failed Response: [${failedResponse.failed()}]")
        } catch (Exception exception) {
            AsaasLogger.error("AsaasErpCustomerAccountManagerService.sendSqsMessageList -> O seguinte erro foi retornado ao enviar a mensagem: ${exception}")
        }

        throw new RuntimeException("Ocorreu um erro ao enviar mensagens em lote para fila SQS.")
    }

    public void setRetroactiveAsFinished(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        if (AsaasEnvironment.isDevelopment()) return

        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()
        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.post("/api/asaas/onboarding-integrations/integrated-customers", [:])

        if (!asaasErpManager.isSuccessful()) {
            if (asaasErpManager.isErrorWithRetryEnabled()) throw new AsaasErpUndefinedErrorException("Ocorreu um erro ao marcar integração de pagador retroativa como finalizada. [CustomerId: ${asaasErpCustomerConfig.customerId}].")

            AsaasLogger.error("AsaasErpCustomerAccountManagerService.setRetroactiveAsFinished -> O seguinte erro ao marcar integração de pagador retroativa como finalizada. [Erro: ${asaasErpManager.getErrorMessage()}]")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }
    }
}
