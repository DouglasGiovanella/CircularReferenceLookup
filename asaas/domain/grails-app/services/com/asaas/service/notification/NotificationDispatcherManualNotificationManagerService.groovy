package com.asaas.service.notification

import com.asaas.annotation.CircuitBreaker
import com.asaas.circuitbreakerregistry.NotificationDispatcherCircuitBreaker
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.integration.notificationdispatcher.NotificationDispatcherManager
import com.asaas.log.AsaasLogger
import com.asaas.notification.dispatcher.dto.NotificationDispatcherCreateManualPaymentNotificationRequestDTO
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class NotificationDispatcherManualNotificationManagerService {

    @CircuitBreaker(name = NotificationDispatcherCircuitBreaker.MANUAL_NOTIFICATION)
    public Map create(Payment payment) {
        NotificationDispatcherCreateManualPaymentNotificationRequestDTO requestDTO = new NotificationDispatcherCreateManualPaymentNotificationRequestDTO(payment)
        NotificationDispatcherManager manager = new NotificationDispatcherManager()
        manager.post("/paymentNotificationRequest/manual/create", requestDTO.properties)

        if (manager.isSuccessful()) return [success: true]
        if (manager.isBadRequest()) throw new BusinessException(manager.getErrorMessage())

        AsaasLogger.error("${this.class.simpleName}.create >> Erro ao solicitar notificação manual. [status: ${manager.getStatusCode()}, errorMessage: ${manager.getErrorMessage()}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }
}
