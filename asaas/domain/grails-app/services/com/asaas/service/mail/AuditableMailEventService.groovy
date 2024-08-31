package com.asaas.service.mail

import com.asaas.domain.email.AuditableMailEvent
import com.asaas.domain.email.RelevantMailHistory
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequest
import com.asaas.email.AsaasEmailEvent
import com.asaas.email.repository.RelevantMailHistoryLoginLinkValidationRequestRepository
import com.asaas.email.adapter.AuditableMailEventAdapter
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.sendgrid.dto.SqsMessageAuditableMailSendGridEventDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import software.amazon.awssdk.services.sqs.model.Message

@Transactional
class AuditableMailEventService {

    private static final String SQS_QUEUE_CONFIG_KEY = "auditableMailEvent"

    public void processPendingSendGridEventSqsMessages() {
        final Integer maxNumberOfMessages = 1000
        final Integer timeoutToReceiveMessagesInSeconds = 10

        SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)

        List<Message> sqsMessageList = sqsManager.receiveMessages(maxNumberOfMessages, timeoutToReceiveMessagesInSeconds)
        if (!sqsMessageList) return

        final Integer flushEvery = 50
        List<Message> sqsMessageToDeleteList = []

        Utils.forEachWithFlushSession(sqsMessageList, flushEvery, { Message sqsMessage ->
            try {
                SqsMessageAuditableMailSendGridEventDTO sqsMessageDTO = GsonBuilderUtils.buildClassFromJson(sqsMessage.body, SqsMessageAuditableMailSendGridEventDTO)

                AuditableMailEventAdapter eventAdapter = new AuditableMailEventAdapter(sqsMessageDTO)

                save(eventAdapter)

                sqsMessageToDeleteList.add(sqsMessage)
            } catch (Exception exception) {
                AsaasLogger.error("AuditableMailEventService.processPendingSendGridEventSqsMessages >> Erro ao processar mensagem [${sqsMessage.messageId()}]", exception)
            }
        })

        sqsManager.deleteBatch(sqsMessageToDeleteList)
    }

    public void saveLoginLinkClickEventIfPossible(LoginLinkValidationRequest loginLinkValidationRequest, Map deviceParams) {
        try {
            Map queryParams = [loginLinkValidationRequestId: loginLinkValidationRequest.id]

            Long mailHistoryEntryId = RelevantMailHistoryLoginLinkValidationRequestRepository.query(queryParams).column("mailHistoryEntry.id").get() as Long
            if (!mailHistoryEntryId) return

            String mailTo = loginLinkValidationRequest.username
            AsaasEmailEvent event = AsaasEmailEvent.LOGIN_LINK_CLICK

            AuditableMailEventAdapter eventAdapter = new AuditableMailEventAdapter(mailTo, event, mailHistoryEntryId, new Date(), deviceParams)

            save(eventAdapter)
        } catch (Exception exception) {
            AsaasLogger.error("AuditableMailEventService.saveLoginLinkClickEventIfPossible >> Erro ao salvar evento de click. LoginLinkValidationRequest: [${loginLinkValidationRequest.id}]", exception)
        }
    }

    private void save(AuditableMailEventAdapter adapter) {
        AuditableMailEvent event = new AuditableMailEvent()
        event.mailTo = adapter.mailTo
        event.eventName = adapter.eventName
        event.remoteIp = adapter.remoteIp
        event.mailHistoryEntry = RelevantMailHistory.read(adapter.mailHistoryEntryId)
        event.eventDate = adapter.eventDate
        event.userAgent = adapter.userAgent?.take(AuditableMailEvent.MAX_USER_AGENT_STRING_SIZE)

        event.save(failOnError: true)
    }
}
