package com.asaas.service.notification

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sqs.SqsManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherPaymentManagerService {

    public void publishInvoiceViewed(Map data) {
        if (AsaasEnvironment.isDevelopment()) return

        Map notificationViewedData = [notificationHistoryId: data.notificationHistoryId, eventDate: new Date()]
        String messageBody = GsonBuilderUtils.toJsonWithoutNullFields(notificationViewedData)

        try {
            SqsManager sqsManager = new SqsManager("notificationDispatcherInvoiceNotificationViewing")
            sqsManager.createSendMessageRequest(messageBody)
            sqsManager.sendMessage()
        } catch (Exception exception) {
            AsaasLogger.error("NotificationDispatcherPaymentManagerService.publishInvoiceViewed >> Ocorreu um erro ao enviar a mensagem. [messageBody: ${messageBody}]", exception)
        }
    }
}
