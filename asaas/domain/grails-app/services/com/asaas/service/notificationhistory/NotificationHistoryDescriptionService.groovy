package com.asaas.service.notificationhistory

import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.notification.NotificationRequestTo
import com.asaas.domain.payment.Payment
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationStatus
import com.asaas.notification.NotificationType
import com.asaas.utils.PhoneNumberUtils

import grails.transaction.Transactional

@Transactional
class NotificationHistoryDescriptionService {

    public String getRefundRequestCreatedNotificationDescription(Payment payment) {
        return "Enviado mensagem de solicitação de estorno da cobrança ${payment.getInvoiceNumber()} para o seu cliente ${payment.customerAccount.name}"
    }

    public String getRefundRequestExpiredNotificationDescription(Payment payment) {
        return "Enviado mensagem de cancelamento de estorno da cobrança ${payment.getInvoiceNumber()} por falta do envio de documentos por mais de ${RefundRequest.DAYS_TO_EXPIRE} dias para o seu cliente ${payment.customerAccount.name}."
    }

    public String getPhoneCallInteractionNotificationDescription(String phoneNumber, String invoiceNumber) {
        return "Notificação por voz. (${PhoneNumberUtils.formatPhoneNumber(phoneNumber)}) (Cobrança ${invoiceNumber})"
    }

    public String buildNotificationHistoryDescription(NotificationStatus notificationStatus, NotificationType notificationType, NotificationEvent notificationEvent, NotificationReceiver notificationReceiver, String receiverNotificationIdentifier, String failReason) {
        String decodedStatus = decodeNotificationStatus(notificationStatus)
        String decodedType = notificationType.getName()
        String decodedEvent = decodeNotificationEvent(notificationEvent)
        String decodedReceiver = decodeNotificationReceiver(notificationReceiver, notificationEvent, receiverNotificationIdentifier)
        String failReasonIfExists = failReason ? " (" + failReason + ")" : ""

        return "${decodedStatus} ${decodedType} de ${decodedEvent} para ${decodedReceiver}${failReasonIfExists}."
    }

    public String buildNotificationEventDescription(NotificationStatus notificationStatus, NotificationRequest notificationRequest, NotificationRequestTo notificationRequestTo, String failReason) {
        String receiverNotificationIdentifier = getReceiverNotificationIdentifier(notificationRequest, notificationRequestTo)
        return buildNotificationHistoryDescription(notificationStatus, notificationRequest.type, notificationRequest.getEventTrigger(), notificationRequest.receiver, receiverNotificationIdentifier, failReason)
    }

    private String decodeNotificationStatus(NotificationStatus notificationStatus) {
        switch (notificationStatus) {
            case (NotificationStatus.SENT): return "Enviado"
            case (NotificationStatus.FAILED): return "Falha no envio da notificação por"
            default: return ""
        }
    }

    private String decodeNotificationEvent(NotificationEvent notificationEvent) {
        switch (notificationEvent) {
            case NotificationEvent.CUSTOMER_CREATED: return "Boas-Vindas"
            case NotificationEvent.CUSTOMER_PAYMENT_RECEIVED: return "confirmação de pagamento"
            case NotificationEvent.CUSTOMER_PAYMENT_OVERDUE: return "cobrança atrasada"
            case NotificationEvent.PAYMENT_DUEDATE_WARNING: return "aviso de vencimento"
            case NotificationEvent.PAYMENT_CREATED: return "geração de cobrança"
            case NotificationEvent.PAYMENT_UPDATED: return "alteração da cobrança"
            case NotificationEvent.SEND_LINHA_DIGITAVEL: return "linha digitável"
            case NotificationEvent.CREDIT_CARD_EXPIRED: return "cartão de crédito expirado"
            case NotificationEvent.PAYMENT_DELETED: return "remoção de cobrança"
            default: return ""
        }
    }

    private String decodeNotificationReceiver(NotificationReceiver notificationReceiver, NotificationEvent notificationEvent, String receiverNotificationIdentifier) {
        String notificationReceiverName = getNotificationReceiverName(notificationReceiver, notificationEvent)
        if (!notificationReceiverName) return ""
        if (receiverNotificationIdentifier) receiverNotificationIdentifier = " (" + receiverNotificationIdentifier + ")"
        return notificationReceiverName + receiverNotificationIdentifier
    }

    private String getNotificationReceiverName(NotificationReceiver notificationReceiver, NotificationEvent notificationEvent) {
        if (notificationReceiver.isCustomer()) return "o seu cliente"
        if (!notificationReceiver.isProvider()) return ""
        if (notificationEvent.isPaymentDeleted()) return "o usuário"

        return "você"
    }

    private String getReceiverNotificationIdentifier(NotificationRequest notificationRequest, NotificationRequestTo notificationRequestTo) {
        NotificationReceiver notificationReceiver = notificationRequest.receiver
        NotificationType notificationType = notificationRequest.type
        NotificationEvent notificationEvent = notificationRequest.getEventTrigger()

        if (notificationReceiver.isCustomer()) {
            if (notificationType.isEmail()) return notificationRequestTo?.email ?: ""
            if (notificationType.isPhoneNumberBased()) {
                return PhoneNumberUtils.formatPhoneNumber(notificationRequest.getNotificationPhoneNumber())
            }

            return ""
        }

        if (notificationReceiver.isProvider()) {
            if (!notificationEvent.isPaymentDeleted() && notificationType.isSms()) {
                return PhoneNumberUtils.formatPhoneNumber(notificationRequest.customerAccount.provider.mobilePhone)
            }

            return notificationRequestTo?.email ?: ""
        }

        return ""
    }
}
