package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentViewingInfo
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.paymentviewinginfo.PaymentViewingInfoSqsMessageDTO
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import software.amazon.awssdk.services.sqs.model.Message

import java.util.concurrent.ConcurrentHashMap

@Transactional
class PaymentViewingInfoSqsMessageService {

    private static final String SQS_QUEUE_CONFIG_KEY = "paymentViewingInfo"

    def customerMessageService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationRequestViewingInfoService
    def notificationDispatcherPaymentManagerService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def paymentService
    def paymentViewingInfoCacheService

    public void saveSqsMessage(Map messageData) {
        if (AsaasEnvironment.isDevelopment()) return

        String messageBody = GsonBuilderUtils.toJsonWithoutNullFields(messageData)

        try {
            SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
            sqsManager.createSendMessageRequest(messageBody)
            sqsManager.sendMessage()
        } catch (Exception exception) {
            AsaasLogger.error("PaymentViewingInfoSqsMessageService.saveSqsMessage >> Ocorreu um erro ao enviar a mensagem para fila SQS. Mensagem: [${messageBody}] Exception: [${exception}]")
        }

        if (messageData.notificationHistoryId) notificationDispatcherPaymentManagerService.publishInvoiceViewed(messageData)
    }

    public void processPaymentViewingInfoSqsMessage() {
        final Integer maxNumberOfMessages = 1000
        final Integer timeoutToReceiveMessagesInSeconds = 10
        SqsManager sqsManager = new SqsManager(SQS_QUEUE_CONFIG_KEY)
        List<Message> sqsMessageList = sqsManager.receiveMessages(maxNumberOfMessages, timeoutToReceiveMessagesInSeconds)

        if (!sqsMessageList) return

        final Integer batchSize = 50
        final Integer flushEvery = 50
        final Integer threadSize = 200

        Map notificationRequestPaymentViewingInfoMap = buildNotificationRequestPaymentViewingInfoMap(sqsMessageList)
        List<Long> notificationRequestPaymentViewingGroupedByPaymentList = notificationRequestPaymentViewingInfoMap.keySet().toList()

        ThreadUtils.processWithThreadsOnDemand(notificationRequestPaymentViewingGroupedByPaymentList, threadSize, { List<Long> notificationRequestPaymentViewingGroupedByPaymentSubList ->
            List<Message> batchSqsMessageList = []

            Utils.forEachWithFlushSessionAndNewTransactionInBatch(notificationRequestPaymentViewingGroupedByPaymentSubList, batchSize, flushEvery, { Long paymentId ->
                List<Map> paymentViewingInfoSqsMessageInfoList = notificationRequestPaymentViewingInfoMap[paymentId]
                for (Map paymentViewingInfoSqsMessageInfo : paymentViewingInfoSqsMessageInfoList) {
                    PaymentViewingInfoSqsMessageDTO paymentViewingInfoSqsMessageDTO = paymentViewingInfoSqsMessageInfo.paymentViewingInfoSqsMessageDTO
                    Payment payment = Payment.read(paymentViewingInfoSqsMessageDTO.paymentId)

                    setPaymentAsViewed(payment, paymentViewingInfoSqsMessageDTO.invoiceViewed, paymentViewingInfoSqsMessageDTO.boletoViewed)

                    if (paymentViewingInfoSqsMessageDTO.notificationRequestId) {
                        notificationRequestViewingInfoService.saveInvoiceViewedIfNecessary(payment.id, paymentViewingInfoSqsMessageDTO.notificationRequestId)
                    }

                    paymentViewingInfoCacheService.evictGetPaymentViewingInfoData(payment.id)

                    Message sqsMessage = paymentViewingInfoSqsMessageInfo.sqsMessage
                    batchSqsMessageList.add(sqsMessage)
                }
            }, [logErrorMessage: "PaymentViewingInfoSqsMessageService.processPaymentViewingInfoSqsMessage >> Erro ao processar visualizacao de cobranca",
                appendBatchToLogErrorMessage: true,
                onEachTransactionEnd: { sqsManager.deleteBatch(batchSqsMessageList) }])
        })
    }

    private void setPaymentAsViewed(Payment payment, Boolean invoiceViewed, Boolean boletoViewed) {
        Boolean invoiceViewedFirstTime = false
        Boolean bankSlipViewedFirstTime = false

        PaymentViewingInfo paymentViewingInfo = PaymentViewingInfo.query([paymentId: payment.id]).get()
        if (!paymentViewingInfo) {
            paymentViewingInfo = new PaymentViewingInfo(payment: payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentViewed(payment)
        }

        if (invoiceViewed) {
            if (!paymentViewingInfo.invoiceViewed) invoiceViewedFirstTime = true

            paymentViewingInfo.invoiceViewed = true
            paymentViewingInfo.invoiceViewedDate = new Date()
        }

        if (boletoViewed) {
            if (!paymentViewingInfo.boletoViewed) bankSlipViewedFirstTime = true

            paymentViewingInfo.boletoViewed = true
            paymentViewingInfo.boletoViewedDate = new Date()
        }

        paymentViewingInfo.save(failOnError: true)

        if (invoiceViewedFirstTime) {
            if (!payment.deleted) customerMessageService.notifyCustomerThatInvoiceHasBeenViewed(payment)

            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CHECKOUT_VIEWED)
        }

        if (bankSlipViewedFirstTime) {
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_BANK_SLIP_VIEWED)
        }
    }

    private Map buildNotificationRequestPaymentViewingInfoMap(List<Message> sqsMessageList) {
        Map notificationRequestPaymentViewingInfoMessageDTODataMap = new ConcurrentHashMap()
        for (Message sqsMessage : sqsMessageList) {
            PaymentViewingInfoSqsMessageDTO paymentViewingInfoSqsMessageDTO = GsonBuilderUtils.buildClassFromJson(sqsMessage.body, PaymentViewingInfoSqsMessageDTO)

            List<Map> paymentViewingInfoSqsMessageDTOList = notificationRequestPaymentViewingInfoMessageDTODataMap[paymentViewingInfoSqsMessageDTO.paymentId]
            if (!paymentViewingInfoSqsMessageDTOList) {
                paymentViewingInfoSqsMessageDTOList = []
                notificationRequestPaymentViewingInfoMessageDTODataMap[paymentViewingInfoSqsMessageDTO.paymentId] = paymentViewingInfoSqsMessageDTOList
            }

            paymentViewingInfoSqsMessageDTOList.add([
                sqsMessage: sqsMessage,
                paymentViewingInfoSqsMessageDTO: paymentViewingInfoSqsMessageDTO,
            ])
        }

        return notificationRequestPaymentViewingInfoMessageDTODataMap
    }
}
