package com.asaas.service.notification

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.PhoneCallNotification
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.domain.webhook.WebhookRequestProvider
import com.asaas.domain.webhook.WebhookRequestType
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.totalVoice.builder.TotalVoiceBuilder
import com.asaas.integration.totalVoice.manager.TotalVoiceManager
import com.asaas.integration.totalVoice.vo.CustomerInteractionWebhookRequestVO
import com.asaas.integration.totalVoice.vo.EndCallWebhookRequestVO
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationMessageType
import com.asaas.notification.NotificationStatus
import com.asaas.notification.PhoneCallNotificationCustomerInteraction
import com.asaas.notification.PhoneCallNotificationStatus
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.util.Environment

import org.apache.commons.lang.RandomStringUtils

@Transactional
class PhoneCallNotificationService {

    static Integer TOTAL_VOICE_FAIL_INVALID_NUMBER_CODE = 11

    def boletoService

    def messageService

    def notificationRequestStatusService

    def timelineEventService

    def webhookRequestService

    def chargedFeeService

    public PhoneCallNotification save(String externalIdentifier, NotificationRequest notificationRequest, Customer customer) {
        PhoneCallNotification phoneCallNotification = new PhoneCallNotification(externalIdentifier, notificationRequest, customer)
        phoneCallNotification.save(flush: true)

        return phoneCallNotification
    }

    public void processPendingWebhookRequests() {
        List<Long> pendingEndCallWebhookRequestList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.TOTAL_VOICE, requestType: WebhookRequestType.TOTAL_VOICE_END_CALL]).list(max: 200)
        for (Long id in pendingEndCallWebhookRequestList) {
            processEndCallWebhookRequest(id)
        }

        List<Long> pendingCustomerInteractionWebhookRequestList = WebhookRequest.pending([column: "id", requestProvider: WebhookRequestProvider.TOTAL_VOICE, requestType: WebhookRequestType.TOTAL_VOICE_CUSTOMER_INTERACTION]).list(max: 200)
        for (Long id in pendingCustomerInteractionWebhookRequestList) {
            processCustomerInteractionWebhookRequest(id)
        }
    }

    private void processEndCallWebhookRequest(Long webhookRequestId) {
        AsaasLogger.debug("Processing TotalVoice EndCall WebhookRequest [${webhookRequestId}]")

        try {
            Utils.withNewTransactionAndRollbackOnError ({ transactionStatus ->
                WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
                if (!webhookRequest.requestBody) throw new Exception("WebhookRequest sem dados")

                EndCallWebhookRequestVO endCallWebhookRequestVO = new EndCallWebhookRequestVO(webhookRequest.requestBody)

                PhoneCallNotification phoneCallNotification = PhoneCallNotification.query([externalIdentifier: endCallWebhookRequestVO.id]).get()
                if (!phoneCallNotification) throw new Exception("phoneCallNotification não encontrada no Asaas")

                updatePhoneCallNotificationFromEndCallWebhookRequestVO(phoneCallNotification, endCallWebhookRequestVO)

                if (phoneCallNotification.notificationRequest) timelineEventService.saveTimelinePhoneCallInteractionForEndPhoneCall(phoneCallNotification.notificationRequest, phoneCallNotification)

                webhookRequestService.setAsProcessed(webhookRequest)
            }, [onError: { e -> throw e}])
        } catch(Exception e) {
            AsaasLogger.error("Problema ao processar um WebhookRequest da TotalVoice. Id [${webhookRequestId}]", e)
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            webhookRequestService.setAsError(webhookRequest)
        }
    }

    private void updatePhoneCallNotificationFromEndCallWebhookRequestVO(PhoneCallNotification phoneCallNotification, EndCallWebhookRequestVO endCallWebhookRequestVO) {
        phoneCallNotification.startDate = endCallWebhookRequestVO.data_inicio
        phoneCallNotification.phoneType = endCallWebhookRequestVO.tipo
        phoneCallNotification.durationCharged = endCallWebhookRequestVO.duracao_cobrada_segundos
        phoneCallNotification.duration = endCallWebhookRequestVO.duracao_segundos
        phoneCallNotification.cost = endCallWebhookRequestVO.preco
        phoneCallNotification.destinationPhoneNumber = endCallWebhookRequestVO.numero_destino
        phoneCallNotification.externalStatus = endCallWebhookRequestVO.status

        phoneCallNotification.status = PhoneCallNotificationStatus.PROCESSED

        phoneCallNotification.save(failOnError: true)
    }

    private void processCustomerInteractionWebhookRequest(Long webhookRequestId) {
        AsaasLogger.debug("Processing TotalVoice CustomerInteraction WebhookRequest [${webhookRequestId}]")

        try {
            Utils.withNewTransactionAndRollbackOnError ({
                WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
                if (!webhookRequest?.requestBody) throw new Exception("WebhookRequest sem dados")

                CustomerInteractionWebhookRequestVO customerInteractionWebhookRequestVO = new CustomerInteractionWebhookRequestVO(webhookRequest.requestBody)

                PhoneCallNotification phoneCallNotification = PhoneCallNotification.query([externalIdentifier: customerInteractionWebhookRequestVO.getId()]).get()
                if (!phoneCallNotification) throw new ResourceNotFoundException("PhoneCallNotification não encontrada no Asaas [External ID: ${customerInteractionWebhookRequestVO.getId()}]")

                updatePhoneCallNotificationFromCustomerInteractionWebhookRequestVO(phoneCallNotification, customerInteractionWebhookRequestVO)

                if (phoneCallNotification.notificationRequest) timelineEventService.saveTimelinePhoneCallInteractionForCustomerInteraction(phoneCallNotification.notificationRequest, phoneCallNotification)

                webhookRequestService.setAsProcessed(webhookRequest)
            }, [onError: { e -> throw e}])
        } catch(Exception e) {
            AsaasLogger.error("Problema ao processar um WebhookRequest da TotalVoice. Id [${webhookRequestId}]", e)
            WebhookRequest webhookRequest = WebhookRequest.get(webhookRequestId)
            webhookRequestService.setAsError(webhookRequest)
        }
    }

    private void updatePhoneCallNotificationFromCustomerInteractionWebhookRequestVO(PhoneCallNotification phoneCallNotification, CustomerInteractionWebhookRequestVO customerInteractionWebhookRequestVO) {

        switch(customerInteractionWebhookRequestVO.ultimo_dtmf) {
            case "1":
                phoneCallNotification.listenedLinhaDigitavel = true
                break
            case "2":
                phoneCallNotification.lastCustomerInteraction = PhoneCallNotificationCustomerInteraction.WILL_PAY_BOLETO_TODAY
                break
            case "3":
                phoneCallNotification.lastCustomerInteraction = PhoneCallNotificationCustomerInteraction.WILL_PAY_BOLETO_IN_THE_NEXT_DAYS
                break
            case "4":
                phoneCallNotification.lastCustomerInteraction = PhoneCallNotificationCustomerInteraction.DOES_NOT_RECOGNIZE_THE_PAYMENT
                break
        }

        phoneCallNotification.save(failOnError: true)
    }

    public Map buildLinhaDigitavelSubMenu(String totalVoiceId) {
        PhoneCallNotification phoneCallNotification = PhoneCallNotification.query([externalIdentifier: totalVoiceId]).get()

        Payment payment = phoneCallNotification.notificationRequest.payment
        NotificationMessageType notificationMessageType = phoneCallNotification.notificationRequest.customerAccount.provider.customerConfig.notificationMessageType

        String linhaDigitavel = PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && !payment.provider.boletoIsDisabled() ? boletoService.getLinhaDigitavel(payment) : ""

        Map responseData = TotalVoiceBuilder.buildLinhaDigitavelSubMenu(linhaDigitavel, notificationMessageType)
        return responseData
    }

    public Map buildDinamicUra(String totalVoiceId) {
        PhoneCallNotification phoneCallNotification = PhoneCallNotification.query([externalIdentifier: totalVoiceId]).get()

        if (!phoneCallNotification) throw new ResourceNotFoundException("PhoneCallNotification não encontrada no Asaas [External ID: ${totalVoiceId}]")

        Payment payment = phoneCallNotification.notificationRequest.payment
        Customer customer = phoneCallNotification.notificationRequest.customerAccount.provider
        String customerName = customer.buildTradingName()
        NotificationMessageType notificationMessageType = customer.customerConfig.notificationMessageType
        Boolean shouldShowBankSlipOptions = PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && !payment.provider.boletoIsDisabled()

        Map responseData = TotalVoiceBuilder.buildDinamicUra(payment.value, payment.dueDate, customerName, notificationMessageType, shouldShowBankSlipOptions)
        return responseData
    }

    public void processNotificationList() {
        if (Holiday.isHoliday(new Date())) return

        List<Long> pendingRequests = NotificationRequest.pendingPhoneCall([column: "id"]).list(max: 1000)

        if (!pendingRequests) return

        for (Long id : pendingRequests) {
            try {
                processNotification(id)
            } catch (Exception e) {
                AsaasLogger.error("Erro ao processar notificacao de ligacao. id: ${id}", e)
            }
        }
    }

    private void processNotification(Long notificationRequestId) {
        Utils.withNewTransactionAndRollbackOnError ({
            NotificationRequest notificationRequest = NotificationRequest.get(notificationRequestId)

            Customer customer = notificationRequest.customerAccount.provider

            if (!customer.hasSufficientBalance(CustomerFee.calculatePhoneCallNotificationFee(customer))) {
                notificationRequestStatusService.updateSendingStatus(notificationRequest, NotificationStatus.FAILED, null, null, false)
                AsaasLogger.warn("PhoneCallNotificationService.processNotification >> Notificação de voz não enviada: falta de saldo do cliente [NotificationRequestID: ${notificationRequestId}]")
                return
            }

            String phoneNumber = notificationRequest.getNotificationPhoneNumber()

            if (!PhoneNumberUtils.validatePhone(phoneNumber) || !PhoneNumberUtils.validateBlockListNumber(phoneNumber)) {
                notificationRequestStatusService.updateSendingStatus(notificationRequest, NotificationStatus.FAILED, null, null, false)
                AsaasLogger.warn("PhoneCallNotificationService.processNotification >> Notificação de voz não enviada: número de telefone inválido [NotificationRequestID: ${notificationRequestId}]")
                return
            }

            Map totalVoiceResponse = sendToTotalVoice(phoneNumber, notificationRequestId)

            if (!totalVoiceResponse.success) {
                if (totalVoiceResponse.failReason?.code == TOTAL_VOICE_FAIL_INVALID_NUMBER_CODE) {
                    notificationRequestStatusService.updateSendingStatus(notificationRequest, NotificationStatus.FAILED, null, totalVoiceResponse.failReason.description, true)
                }

                return
            }

            notificationRequestStatusService.updateSendingStatus(notificationRequest, NotificationStatus.SENT, null, null, true)

            PhoneCallNotification phoneCallNotification = save(totalVoiceResponse.totalVoiceId, notificationRequest, customer)

            chargedFeeService.savePhoneCallNotificationFee(customer, phoneCallNotification)
        })
    }

    private Map sendToTotalVoice(String phoneNumber, Long id) {
        if (!Environment.getCurrent().equals(Environment.PRODUCTION)) {
            return [success: true, totalVoiceId: RandomStringUtils.randomNumeric(8)]
        }

        AsaasLogger.debug("Enviando ligacao para a TotalVoice. notificationRequestId: ${id}")

        TotalVoiceManager totalVoiceManager = new TotalVoiceManager()
        totalVoiceManager.sendComposto(phoneNumber, id)

        if (!totalVoiceManager.success || !totalVoiceManager.responseBodyMap.dados || !totalVoiceManager.responseBodyMap.dados.id) {
            if (totalVoiceManager.responseBodyMap?.motivo == TOTAL_VOICE_FAIL_INVALID_NUMBER_CODE) {
                AsaasLogger.warn("Campo inválido ao enviar ligacao para a TotalVoice. notificationRequestId: ${id}. body: ${totalVoiceManager.responseBodyMap}")
                return [success: false, failReason: [code: totalVoiceManager.responseBodyMap.motivo, description: Utils.getMessageProperty('phoneCallNotification.totalVoice.invalid.number')]]
            }
            AsaasLogger.error("Erro ao enviar ligacao para a TotalVoice. notificationRequestId: ${id}. body: ${totalVoiceManager.responseBodyMap}")
            return [success: false]
        }

        return [success: totalVoiceManager.success, totalVoiceId: totalVoiceManager.responseBodyMap.dados.id.toString()]
    }
}
