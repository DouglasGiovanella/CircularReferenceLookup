package com.asaas.service.login.thirdpartyloginvalidation

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.login.thirdpartyloginvalidation.ThirdPartyLoginValidationAdapter
import com.asaas.login.thirdpartyloginvalidation.ThirdPartyLoginValidationSqsMessageDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.Message

@Transactional
class ThirdPartyLoginValidationSqsMessageService {

    private static final String SQS_QUEUE_CONFIG_KEY = "thirdPartyLoginValidation"

    def thirdPartyLoginValidationService

    public void saveSqsMessage(Map messageData) {
        if (!AsaasEnvironment.isProduction()) return

        String messageBody = GsonBuilderUtils.toJsonWithoutNullFields(messageData)

        try {
            SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
            sqsManager.createSendMessageRequest(messageBody)
            sqsManager.sendMessage()
        } catch (Exception exception) {
            AsaasLogger.error("ThirdPartyLoginValidationSqsMessageService.saveSqsMessage >> Ocorreu um erro ao enviar a mensagem para fila SQS. Mensagem: [${messageBody}]", exception)
        }
    }

    public void processThirdPartyLoginValidationSqsMessage() {
        final Integer maxNumberOfMessages = 1500
        final Integer timeoutToReceiveMessagesInSeconds = 10

        try {
            SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
            List<Message> sqsMessageList = sqsManager.receiveMessages(maxNumberOfMessages, timeoutToReceiveMessagesInSeconds)
            if (!sqsMessageList) return

            List<ThirdPartyLoginValidationAdapter> thirdPartyLoginValidationAdapterList = []
            for (Message sqsMessage : sqsMessageList) {
                ThirdPartyLoginValidationSqsMessageDTO thirdPartyLoginValidationSqsMessage = GsonBuilderUtils.buildClassFromJson(sqsMessage.body, ThirdPartyLoginValidationSqsMessageDTO)
                if (!validateThirdPartyLoginValidationSqsMessage(thirdPartyLoginValidationSqsMessage)) {
                    AsaasLogger.error("ThirdPartyLoginValidationSqsMessageService.processThirdPartyLoginValidationSqsMessage >> Não foi possível processar a mensagem [${sqsMessage.messageId()}]. " +
                        "Username [${thirdPartyLoginValidationSqsMessage.username}]. " +
                        "LoginAttemptDate [${thirdPartyLoginValidationSqsMessage.loginAttemptDate}]")
                    continue
                }
                thirdPartyLoginValidationAdapterList.add(new ThirdPartyLoginValidationAdapter(thirdPartyLoginValidationSqsMessage))
            }

            thirdPartyLoginValidationService.saveInBatch(thirdPartyLoginValidationAdapterList)

            sqsManager.deleteBatch(sqsMessageList)
        } catch (Exception exception) {
            AsaasLogger.error("ThirdPartyLoginValidationSqsMessageService.processThirdPartyLoginValidationSqsMessage >> Ocorreu um erro ao salvar o resultado da validadação do login de terceiros em lote", exception)
        }
    }

    private Boolean validateThirdPartyLoginValidationSqsMessage(ThirdPartyLoginValidationSqsMessageDTO thirdPartyLoginValidationSqsMessage) {
        if (Utils.isEmptyOrNull(thirdPartyLoginValidationSqsMessage.username)) return false

        if (Utils.isEmptyOrNull(thirdPartyLoginValidationSqsMessage.loginAttemptDate)) return false

        return true
    }
}
