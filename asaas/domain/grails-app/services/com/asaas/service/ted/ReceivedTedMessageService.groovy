package com.asaas.service.ted

import com.asaas.domain.ted.ReceivedTedMessage
import com.asaas.integration.jdspb.api.dto.JdSpbGetMessageResponseDTO
import com.asaas.integration.jdspb.api.utils.JdSpbUtils
import com.asaas.log.AsaasLogger
import com.asaas.ted.ReceivedTedMessageStatus
import com.asaas.ted.adapter.TedAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivedTedMessageService {

    private final static Integer CYCLE_REQUESTS_LIMIT = 1000
    private final static String EMPTY_RETURN_CODE = "ALS01"
    private final static String API_ERROR_RETURN_CODE = "ALE01"

    def jdSpbTedManagerService
    def tedTransactionService

    public Boolean receiveMessages() {
        Boolean hasMessagesToReceive = true

        for (Integer requestNumber = 1; requestNumber <= ReceivedTedMessageService.CYCLE_REQUESTS_LIMIT; requestNumber++) {
            JdSpbGetMessageResponseDTO messageResponseDTO = jdSpbTedManagerService.receiveMessage()

            if (messageResponseDTO) {
                if (isEmptyReturnCode(messageResponseDTO.returnCode)) {
                    hasMessagesToReceive = false
                    break
                }

                Utils.withNewTransactionAndRollbackOnError({
                    saveMessage(messageResponseDTO)
                }, [logErrorMessage: "ReceivedTedMessageService.receiveMessages >> erro ao salvar mensagem de ted. externalIdentifier: [${messageResponseDTO.externalIdentifier}]"])

                if (isApiErrorReturnCode(messageResponseDTO.returnCode)) {
                    hasMessagesToReceive = false
                    break
                }
            } else {
                AsaasLogger.error("ReceivedTedMessageService.receiveMessages() >> Não foi possível recuperar a mensagem!")
            }
        }

        return hasMessagesToReceive
    }

    public void findMessageByExternalIdentifier(String externalIdentifier) {
        JdSpbGetMessageResponseDTO messageResponseDTO = jdSpbTedManagerService.findMessageByExternalIdentifier(externalIdentifier)

        if (!messageResponseDTO) return

        if (isEmptyReturnCode(messageResponseDTO.returnCode)) return

        saveMessage(messageResponseDTO)
    }

    public Boolean processPendingMessages() {
        final Integer messageListLimit = 4000
        final Integer flushEvery = 50
        final Integer batchSize = 50

        List<Long> tedMessageIdList = ReceivedTedMessage.query([column: "id", "status": ReceivedTedMessageStatus.PENDING, disableSort: true]).list(max: messageListLimit)

        if (!tedMessageIdList) return false

        List<Long> tedMessageForRemoveIdList = []
        List<Map> tedMessageWithErrorMapList = []
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(tedMessageIdList, batchSize, flushEvery, { Long tedMessageId ->
            try {
                ReceivedTedMessage tedMessage = ReceivedTedMessage.get(tedMessageId)

                if (!tedMessage.status.isPending()) {
                    AsaasLogger.warn("TedMessageQueueService.processPendingMessages() >> Mensagem não está pendente! ReceivedTedMessageId: ${tedMessageId}")
                    return
                }

                TedAdapter tedAdapter = new TedAdapter(tedMessage)
                if (!JdSpbUtils.isSupportedCodeMessage(tedAdapter.messageCode)) {
                    tedMessageWithErrorMapList += [tedMessageId: tedMessageId, errorMessage: "TedMessageQueueService.processPendingMessages() >> Código da mensagem não suportado! Código: ${tedAdapter.messageCode}"]
                    return
                }

                if (isInvalidAutomationStartDate(tedAdapter.transactionDate, tedAdapter.messageCode)) {
                    tedMessageWithErrorMapList += [tedMessageId: tedMessageId, errorMessage: "Mensagem anterior a data de inicio do processamento! Código: ${tedAdapter.messageCode}"]
                    return
                }

                tedTransactionService.saveCreditTedTransaction(tedAdapter)
                tedMessageForRemoveIdList += tedMessageId
            } catch (Exception exception) {
                if (!Utils.isLock(exception)) {
                    String errorMessage
                    if (exception instanceof ValidationException) {
                        errorMessage = DomainUtils.getValidationMessagesAsString(exception.errors)
                    } else {
                        errorMessage = exception.message
                    }
                    tedMessageWithErrorMapList += [tedMessageId: tedMessageId, errorMessage: errorMessage]

                    AsaasLogger.error("ReceivedTedMessageService.processPendingMessages >> Erro ao processar mensagem de TED. [receivedTedMessageId: ${tedMessageId}]")
                }

                throw exception
            }
        }, [logErrorMessage: "ReceivedTedMessageService.processPendingMessages >> Erro ao processar criação da TedTransacion ",
            appendBatchToLogErrorMessage: true,
            onError: { tedMessageForRemoveIdList = [] }])

        if (tedMessageWithErrorMapList) setListAsErrorWithNewTransaction(tedMessageWithErrorMapList)

        if (tedMessageForRemoveIdList) {
            ReceivedTedMessage.executeUpdate("DELETE from ReceivedTedMessage rtm WHERE rtm.id in :tedMessageForRemoveIdList", [tedMessageForRemoveIdList: tedMessageForRemoveIdList])
        }

        return true
    }

    private Boolean isInvalidAutomationStartDate(Date transactionDate, String messageCode) {
        Date automationStartDate = CustomDateUtils.fromString("18/01/2024")
        if (messageCode == "STR0008R2") {
            automationStartDate = CustomDateUtils.fromString("23/01/2024")
        }

        return transactionDate < automationStartDate
    }

    private void saveMessage(JdSpbGetMessageResponseDTO messageResponseDTO) {
        ReceivedTedMessage receivedTedMessage = new ReceivedTedMessage()
        receivedTedMessage.messageBody = messageResponseDTO.messageBody
        receivedTedMessage.status = ReceivedTedMessageStatus.PENDING

        if (isApiErrorReturnCode(messageResponseDTO.returnCode)) receivedTedMessage.status = ReceivedTedMessageStatus.ERROR

        receivedTedMessage.save(failOnError: true)
    }

    private Boolean isEmptyReturnCode(String returnCode) {
        return ReceivedTedMessageService.EMPTY_RETURN_CODE == returnCode
    }

    private Boolean isApiErrorReturnCode(String returnCode) {
        return ReceivedTedMessageService.API_ERROR_RETURN_CODE == returnCode
    }

    private void setListAsErrorWithNewTransaction(List<Map> tedMessageWithErrorMapList) {
        final Integer flushEvery = 100
        final Integer batchSize = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(tedMessageWithErrorMapList, batchSize, flushEvery, { Map tedMessageErrorMap ->
            ReceivedTedMessage tedMessage = ReceivedTedMessage.get(tedMessageErrorMap.tedMessageId)
            tedMessage.status = ReceivedTedMessageStatus.ERROR
            tedMessage.errorMessage = Utils.truncateString(tedMessageErrorMap.errorMessage, 255)
            tedMessage.save(failOnError: true)
        }, [logErrorMessage: "ReceivedTedMessageService.setAsErrorWithNewTransaction >>> Erro ao alterar a status do mensagem para ERROR", appendBatchToLogErrorMessage: true] )
    }
}
