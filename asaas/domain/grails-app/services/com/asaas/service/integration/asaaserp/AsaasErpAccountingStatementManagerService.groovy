package com.asaas.service.integration.asaaserp

import com.asaas.asaaserp.AsaasErpActionType
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.exception.BusinessException
import com.asaas.integration.asaaserp.adapter.accountingstatement.AsaasErpAccountingStatementAdapter
import com.asaas.integration.asaaserp.api.AsaasErpManager
import com.asaas.integration.asaaserp.dto.accountingstatement.AsaasErpAccountingStatementRequestDTO
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse

@Transactional
class AsaasErpAccountingStatementManagerService {

    public void createOrUpdate(List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList) {
        sendSqsMessageList(asaasErpAccountingStatementAdapterList, AsaasErpActionType.CREATED)
    }

    public void delete(List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList) {
        sendSqsMessageList(asaasErpAccountingStatementAdapterList, AsaasErpActionType.DELETED)
    }

    public void setRetroactiveAsFinished(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        if (AsaasEnvironment.isDevelopment()) return

        String apiKey = asaasErpCustomerConfig.getDecryptedApiKey()
        AsaasErpManager asaasErpManager = new AsaasErpManager(apiKey)
        asaasErpManager.isLegacy = false
        asaasErpManager.post("/api/asaas/onboarding-integrations/integrated-payments", [:])

        if (!asaasErpManager.isSuccessful()) {
            if (asaasErpManager.isErrorWithRetryEnabled()) throw new AsaasErpUndefinedErrorException("Ocorreu um erro ao marcar integração de cobranças retroativas como finalizada. [CustomerId: ${asaasErpCustomerConfig.customerId}].")

            AsaasLogger.error("AsaasErpAccountingStatementManagerService.setRetroactiveAsFinished -> O seguinte erro ao marcar integração de cobranças retroativas como finalizada. [Erro: ${asaasErpManager.getErrorMessage()}]")
            throw new BusinessException(asaasErpManager.getErrorMessage())
        }
    }

    private void sendSqsMessageList(List<AsaasErpAccountingStatementAdapter> asaasErpAccountingStatementAdapterList, AsaasErpActionType type) {
        List<SendMessageBatchRequestEntry> messagesList = []
        for (AsaasErpAccountingStatementAdapter asaasErpAccountingStatementAdapter : asaasErpAccountingStatementAdapterList) {
            AsaasErpAccountingStatementRequestDTO asaasErpAccountingStatementRequestDTO = new AsaasErpAccountingStatementRequestDTO(asaasErpAccountingStatementAdapter, type)
            String messageBody = GsonBuilderUtils.toJsonWithoutNullFields(asaasErpAccountingStatementRequestDTO)
            String paymentPublicId = asaasErpAccountingStatementAdapter.paymentPublicId ?: asaasErpAccountingStatementAdapter.paymentOriginPublicId
            String messageId = "${paymentPublicId}_${UUID.randomUUID()}"

            messagesList.add(
                SendMessageBatchRequestEntry
                    .builder()
                    .id(messageId)
                    .messageDeduplicationId(messageId)
                    .messageGroupId(paymentPublicId)
                    .messageBody(messageBody)
                    .build()
            )
        }

        final String SQS_QUEUE_CONFIG_KEY = "asaasErpPaymentIntegration"
        final String SQS_ACCESS_CONFIG = "asaasErpPaymentIntegration"

        try {
            SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY, SQS_ACCESS_CONFIG)
            List<SendMessageBatchResponse> batchesResponseList = sqsManager.sendMessagesListInBatches(messagesList)

            SendMessageBatchResponse failedResponse = batchesResponseList.find { it.hasFailed() }
            if (!failedResponse) return

            AsaasLogger.error("AsaasErpAccountingStatementManagerService.sendSqsMessageList -> Erro ao enviar mensagens em lote para fila SQS. Failed Response: [${failedResponse.failed()}]")
        } catch (Exception exception) {
            AsaasLogger.error("AsaasErpAccountingStatementManagerService.sendSqsMessageList -> O seguinte erro foi retornado ao enviar a mensagem: ${exception}")
        }

        throw new RuntimeException("Ocorreu um erro ao enviar mensagens em lote para fila SQS.")
    }
}
