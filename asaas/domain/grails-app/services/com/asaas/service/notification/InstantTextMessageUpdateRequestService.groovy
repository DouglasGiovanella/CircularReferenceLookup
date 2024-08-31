package com.asaas.service.notification

import com.asaas.domain.notification.InstantTextMessage
import com.asaas.domain.notification.InstantTextMessageUpdateRequest
import com.asaas.exception.BusinessException
import com.asaas.exception.NotificationRequestViewingInfoException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.instanttextmessage.enums.InstantTextMessageUpdateRequestStatus
import com.asaas.integration.instanttextmessage.twilio.adapter.TwilioWhatsAppNotificationWebhookDTO
import com.asaas.integration.instanttextmessage.twilio.parser.TwilioWhatsAppNotificationErrorReasonParser
import com.asaas.log.AsaasLogger
import com.asaas.notification.InstantTextMessageStatus
import com.asaas.notification.InstantTextMessageType
import com.asaas.notification.vo.InstantTextMessageUpdateRequestVO
import com.asaas.notificationrequest.vo.NotificationRequestUpdateSendingStatusVO
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InstantTextMessageUpdateRequestService {

    def dataSource
    def instantTextMessageService
    def notificationRequestStatusService
    def notificationRequestViewingInfoService

    public void saveInBatch(List<TwilioWhatsAppNotificationWebhookDTO> twilioWhatsAppNotificationWebhookDTOList) {
        List<Map> instantTextMessageUpdateRequestMapList = twilioWhatsAppNotificationWebhookDTOList.collect { TwilioWhatsAppNotificationWebhookDTO twilioWhatsAppNotificationWebhookDTO ->
            return [
                "external_identifier": twilioWhatsAppNotificationWebhookDTO.MessageSid,
                "instant_text_message_update_request_status": InstantTextMessageUpdateRequestStatus.PENDING.toString(),
                "status": InstantTextMessageStatus.convert(twilioWhatsAppNotificationWebhookDTO.MessageStatus).toString(),
                "error_code": twilioWhatsAppNotificationWebhookDTO.ErrorCode,
                "error_reason": twilioWhatsAppNotificationWebhookDTO.ErrorCode ? TwilioWhatsAppNotificationErrorReasonParser.parse(twilioWhatsAppNotificationWebhookDTO.errorCode).toString() : null,
                "date_created": new Date(),
                "last_updated": new Date(),
                "deleted": false,
                "version": 0
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "instant_text_message_update_request", instantTextMessageUpdateRequestMapList)
    }

    public void processPendingInstantTextMessageUpdateRequest() {
        final Integer maxItemsPerExecution = 500
        final Integer minItemsPerThread = 50

        List<String> instantMessageUpdateExternalIdList = InstantTextMessageUpdateRequest.query([
            disableSort: true,
            distinct: "externalIdentifier",
            instantTextMessageUpdateRequestStatus: InstantTextMessageUpdateRequestStatus.PENDING
        ]).list(max: maxItemsPerExecution)

        if (!instantMessageUpdateExternalIdList) return

        List<NotificationRequestUpdateSendingStatusVO> updateSendingStatusVOList = Collections.synchronizedList(new ArrayList<NotificationRequestUpdateSendingStatusVO>())

        ThreadUtils.processWithThreadsOnDemand(instantMessageUpdateExternalIdList, minItemsPerThread, { List<String> externalIdList ->
            for (String externalId : externalIdList) {
                List<Long> instantTextMessageUpdateRequestIdList = InstantTextMessageUpdateRequest.query([
                    column: "id",
                    externalIdentifier: externalId,
                    instantTextMessageUpdateRequestStatus: InstantTextMessageUpdateRequestStatus.PENDING,
                    order: "asc"
                ]).list()

                for (Long instantTextMessageUpdateRequestId : instantTextMessageUpdateRequestIdList) {
                    try {
                        Utils.withNewTransactionAndRollbackOnError({
                            InstantTextMessageUpdateRequest instantTextMessageUpdateRequest = InstantTextMessageUpdateRequest.get(instantTextMessageUpdateRequestId)

                            Map instantTextMessageData = InstantTextMessage.query([
                                columnList: ["id", "status", "notificationRequest.id"],
                                type: InstantTextMessageType.WHATSAPP,
                                externalIdentifier: instantTextMessageUpdateRequest.externalIdentifier
                            ]).get() as Map

                            if (!instantTextMessageData) {
                                throw new ResourceNotFoundException("InstantTextMessage (Twilio/WhatsApp) não encontrada [External ID: ${ instantTextMessageUpdateRequest.externalIdentifier }]")
                            }

                            InstantTextMessageUpdateRequestVO instantTextMessageUpdateRequestVO = new InstantTextMessageUpdateRequestVO(instantTextMessageUpdateRequest, instantTextMessageData)
                            InstantTextMessageStatus oldInstantTextMessageStatus = instantTextMessageUpdateRequestVO.oldStatus
                            InstantTextMessageStatus newInstantTextMessageStatus = instantTextMessageUpdateRequestVO.newStatus

                            if (!oldInstantTextMessageStatus.canUpdateTo(newInstantTextMessageStatus)) {
                                AsaasLogger.info("InstantTextMessageUpdateRequestService.processPendingInstantTextMessageUpdateRequest >> O status [${ oldInstantTextMessageStatus }] não pode ser atualizado para [${ newInstantTextMessageStatus }] ${ instantTextMessageUpdateRequest.externalIdentifier }")
                                instantTextMessageUpdateRequest.delete()
                                return
                            }

                            validateUpdateRequest(instantTextMessageUpdateRequestVO)
                            processUpdateRequest(instantTextMessageUpdateRequestVO)

                            if (!oldInstantTextMessageStatus.isAlreadySent() && newInstantTextMessageStatus.isAlreadySent()) {
                                updateSendingStatusVOList.add(notificationRequestStatusService.buildSentObject(instantTextMessageUpdateRequestVO.notificationRequestId, null, true))
                            } else if (!oldInstantTextMessageStatus.isSendingFailure() && newInstantTextMessageStatus.isSendingFailure()) {
                                updateSendingStatusVOList.add(notificationRequestStatusService.buildFailedObject(instantTextMessageUpdateRequestVO.notificationRequestId, instantTextMessageUpdateRequestVO.errorReason.getMessage(), true))
                            }

                            instantTextMessageUpdateRequest.delete()
                        }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
                    } catch (Exception exception) {
                        if (exception instanceof NotificationRequestViewingInfoException) return

                        AsaasLogger.error("InstantTextMessageUpdateRequestService.processPendingInstantTextMessageUpdateRequest >> Erro ao processar InstantTextMessageUpdateRequest [${ instantTextMessageUpdateRequestId }]", exception)

                        Utils.withNewTransactionAndRollbackOnError({
                            InstantTextMessageUpdateRequest instantTextMessageUpdateRequest = InstantTextMessageUpdateRequest.get(instantTextMessageUpdateRequestId)
                            instantTextMessageUpdateRequest.instantTextMessageUpdateRequestStatus = InstantTextMessageUpdateRequestStatus.ERROR
                            instantTextMessageUpdateRequest.save(failOnError: true)
                        })
                    }
                }
            }
        })

        notificationRequestStatusService.saveAsyncUpdateSendingStatusList(updateSendingStatusVOList)
    }

    private void validateUpdateRequest(InstantTextMessageUpdateRequestVO instantTextMessageUpdateRequestVO) {
        String instantTextMessageIdentifier = "InstantTextMessage [ID: ${ instantTextMessageUpdateRequestVO.instantTextMessageId }][External ID: ${ instantTextMessageUpdateRequestVO.externalIdentifier }]"

        InstantTextMessageStatus instantTextMessageStatus = instantTextMessageUpdateRequestVO.oldStatus
        if (!InstantTextMessageStatus.processableStatusList().contains(instantTextMessageStatus)) {
            throw new BusinessException("O status [${ instantTextMessageStatus }] não permite a atualização do webhook ${ instantTextMessageIdentifier }")
        }
    }

    private void processUpdateRequest(InstantTextMessageUpdateRequestVO instantTextMessageUpdateRequestVO) {
        InstantTextMessageStatus newInstantTextMessageStatus = instantTextMessageUpdateRequestVO.newStatus

        Utils.withNewTransactionAndRollbackOnError({
            InstantTextMessage instantTextMessage = InstantTextMessage.get(instantTextMessageUpdateRequestVO.instantTextMessageId)

            if (newInstantTextMessageStatus.isSendingFailure()) {
                instantTextMessage.errorReason = instantTextMessageUpdateRequestVO.errorReason

                if (instantTextMessage.errorReason.isCriticalFailureReason()) {
                    AsaasLogger.info("InstantTextMessageUpdateRequestService.processUpdateRequest >> Falha crítica no envio de notificação InstantTextMessage [ID: ${ instantTextMessage.id }][ErrorCode: ${ instantTextMessageUpdateRequestVO.errorCode }][ErrorReason: ${ instantTextMessage.errorReason.toString() }]")
                }
            }

            instantTextMessage.status = newInstantTextMessageStatus
            instantTextMessage.save(failOnError: true)
        }, [onError: { Exception exception -> throw exception }])

        Utils.withNewTransactionAndRollbackOnError({
            InstantTextMessage instantTextMessage = InstantTextMessage.read(instantTextMessageUpdateRequestVO.instantTextMessageId)

            if (newInstantTextMessageStatus.isRead()) {
                notificationRequestViewingInfoService.saveNotificationViewedIfNecessary(instantTextMessage.notificationRequest, new Date())
            }

            instantTextMessageService.chargeInstantTextMessageIfNecessary(instantTextMessage)
        })
    }
}
