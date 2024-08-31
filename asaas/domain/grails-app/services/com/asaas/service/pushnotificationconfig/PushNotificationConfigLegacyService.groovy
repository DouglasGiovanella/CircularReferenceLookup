package com.asaas.service.pushnotificationconfig

import com.asaas.domain.customer.Customer
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.pushnotification.PushNotificationConfigApplication
import com.asaas.domain.pushnotification.PushNotificationSendType
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.domain.pushnotification.PushNotificationType

import grails.transaction.Transactional

@Transactional
class PushNotificationConfigLegacyService {

    def pushNotificationConfigService

    public PushNotificationConfig createOrUpdatePushNotificationConfig(Customer customer, Map params) {
        if (params.url?.trim() && !params.url.startsWith("http://") && !params.url.startsWith("https://")) {
            params.url = "http://" + params.url
        }

        PushNotificationConfigApplication application = PushNotificationConfigApplication.convert(params.application)

        params.sendType = PushNotificationSendType.SEQUENTIALLY
        params.name = buildNameFromType(params.type, application)

        List<PushNotificationRequestEvent> events = []
        for (PushNotificationRequestEvent event : buildEventsFromPushNotificationType(params.type)) {
            if (isEventAllowed(customer, event)) {
                events.add(event)
            }
        }

        params.events = events

        Map search = [provider: customer, type: PushNotificationType.convert(params.type)]
        if (params.containsKey("application")) search.application = application

        PushNotificationConfig pushNotificationConfig = PushNotificationConfig.query(search).get()
        if (pushNotificationConfig) {
            pushNotificationConfig = pushNotificationConfigService.update(pushNotificationConfig, params)
        } else {
            pushNotificationConfig = pushNotificationConfigService.save(customer, params)
        }

        return pushNotificationConfig
    }

    public List<PushNotificationRequestEvent> buildEventsFromPushNotificationType(PushNotificationType type) {
        if (type.isTransfer()) return listTransferLegacyEvents()

        if (type.isInvoice()) return listInvoiceLegacyEvents()

        if (type.isPayment()) return listPaymentLegacyEvents()

        if (type.isBill()) return listBillLegacyEvents()

        if (type.isReceivableAnticipation()) return listReceivableAnticipationLegacyEvents()

        if (type.isPix()) return listPixLegacyEvents()

        if (type.isMobilePhoneRecharge()) return listMobilePhoneRechargeLegacyEvents()

        if (type.isAccountStatus()) return listAccountStatusLegacyEvents()

        AsaasLogger.error("PushNotificationConfigLegacyService.buildEventsFromPushNotificationType >> O tipo [${type}] não foi tratado")
        throw new RuntimeException("O tipo [${type}] não foi tratado")
    }

    private Boolean isEventAllowed(Customer customer, PushNotificationRequestEvent event) {
        if (event == PushNotificationRequestEvent.PAYMENT_CREATED) {
            final List<Long> CUSTOMER_ID_LIST_TO_IGNORED_CREATED = [485273L, 2375888L, 2375636L]
            if (CUSTOMER_ID_LIST_TO_IGNORED_CREATED.contains(customer.id)) return false

            if (customer.accountOwner && CUSTOMER_ID_LIST_TO_IGNORED_CREATED.contains(customer.accountOwner.id)) return false
        }

        if (event == PushNotificationRequestEvent.PAYMENT_PARTIALLY_REFUNDED) {
            Long zroCustomerId = 2375888L

            return customer.id == zroCustomerId || customer.accountOwner?.id == zroCustomerId
        }

        return true
    }

    private String buildNameFromType(PushNotificationType type, PushNotificationConfigApplication application) {
        if (type.isTransfer()) return "Webhook para transferências"

        if (type.isInvoice()) return "Webhook para notas fiscais"

        if (type.isPayment()) {
            final String paymentWebhookDefaultName = "Webhook para cobranças"
            if (application?.isPluga()) {
                return paymentWebhookDefaultName + " (Pluga)"
            }
            return paymentWebhookDefaultName
        }

        if (type.isBill()) return "Webhook para pague contas"

        if (type.isReceivableAnticipation()) return "Webhook para antecipações"

        if (type.isPix()) return "Webhook para Pix"

        if (type.isMobilePhoneRecharge()) return "Webhook para recargas de celular"

        if (type.isAccountStatus()) return "Webhook de situação da conta"

        AsaasLogger.error("PushNotificationConfigLegacyService.buildNameFromType >> O tipo [${type}] não foi tratado")
        throw new RuntimeException("O tipo [${type}] não foi tratado")
    }

    private List<PushNotificationRequestEvent> listTransferLegacyEvents() {
        return [PushNotificationRequestEvent.TRANSFER_CREATED,
                PushNotificationRequestEvent.TRANSFER_PENDING,
                PushNotificationRequestEvent.TRANSFER_IN_BANK_PROCESSING,
                PushNotificationRequestEvent.TRANSFER_BLOCKED,
                PushNotificationRequestEvent.TRANSFER_DONE,
                PushNotificationRequestEvent.TRANSFER_FAILED,
                PushNotificationRequestEvent.TRANSFER_CANCELLED]
    }

    private List<PushNotificationRequestEvent> listInvoiceLegacyEvents() {
        return [PushNotificationRequestEvent.INVOICE_CREATED,
                PushNotificationRequestEvent.INVOICE_UPDATED,
                PushNotificationRequestEvent.INVOICE_SYNCHRONIZED,
                PushNotificationRequestEvent.INVOICE_AUTHORIZED,
                PushNotificationRequestEvent.INVOICE_PROCESSING_CANCELLATION,
                PushNotificationRequestEvent.INVOICE_CANCELED,
                PushNotificationRequestEvent.INVOICE_CANCELLATION_DENIED,
                PushNotificationRequestEvent.INVOICE_ERROR]
    }

    private List<PushNotificationRequestEvent> listPaymentLegacyEvents() {
        return [PushNotificationRequestEvent.PAYMENT_AUTHORIZED,
                PushNotificationRequestEvent.PAYMENT_AWAITING_RISK_ANALYSIS,
                PushNotificationRequestEvent.PAYMENT_APPROVED_BY_RISK_ANALYSIS,
                PushNotificationRequestEvent.PAYMENT_REPROVED_BY_RISK_ANALYSIS,
                PushNotificationRequestEvent.PAYMENT_CREATED,
                PushNotificationRequestEvent.PAYMENT_UPDATED,
                PushNotificationRequestEvent.PAYMENT_CONFIRMED,
                PushNotificationRequestEvent.PAYMENT_RECEIVED,
                PushNotificationRequestEvent.PAYMENT_ANTICIPATED,
                PushNotificationRequestEvent.PAYMENT_OVERDUE,
                PushNotificationRequestEvent.PAYMENT_DELETED,
                PushNotificationRequestEvent.PAYMENT_RESTORED,
                PushNotificationRequestEvent.PAYMENT_REFUNDED,
                PushNotificationRequestEvent.PAYMENT_REFUND_IN_PROGRESS,
                PushNotificationRequestEvent.PAYMENT_RECEIVED_IN_CASH_UNDONE,
                PushNotificationRequestEvent.PAYMENT_CHARGEBACK_REQUESTED,
                PushNotificationRequestEvent.PAYMENT_CHARGEBACK_DISPUTE,
                PushNotificationRequestEvent.PAYMENT_AWAITING_CHARGEBACK_REVERSAL,
                PushNotificationRequestEvent.PAYMENT_DUNNING_RECEIVED,
                PushNotificationRequestEvent.PAYMENT_DUNNING_REQUESTED,
                PushNotificationRequestEvent.PAYMENT_BANK_SLIP_VIEWED,
                PushNotificationRequestEvent.PAYMENT_CHECKOUT_VIEWED,
                PushNotificationRequestEvent.PAYMENT_CREDIT_CARD_CAPTURE_REFUSED]
    }

    private List<PushNotificationRequestEvent> listBillLegacyEvents() {
        return [PushNotificationRequestEvent.BILL_CREATED,
                PushNotificationRequestEvent.BILL_PENDING,
                PushNotificationRequestEvent.BILL_BANK_PROCESSING,
                PushNotificationRequestEvent.BILL_PAID,
                PushNotificationRequestEvent.BILL_CANCELLED,
                PushNotificationRequestEvent.BILL_FAILED,
                PushNotificationRequestEvent.BILL_REFUNDED]
    }

    private List<PushNotificationRequestEvent> listReceivableAnticipationLegacyEvents() {
        return [PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_CANCELLED,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_SCHEDULED,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_PENDING,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_CREDITED,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_DEBITED,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_DENIED,
                PushNotificationRequestEvent.RECEIVABLE_ANTICIPATION_OVERDUE]
    }

    private List<PushNotificationRequestEvent> listPixLegacyEvents() {
        return [PushNotificationRequestEvent.PIX_QR_CODE_PAID,
                PushNotificationRequestEvent.PIX_QR_CODE_PAYMENT_REFUSED,
                PushNotificationRequestEvent.PIX_DEBIT_REFUNDED,
                PushNotificationRequestEvent.PIX_CREDIT_RECEIVED,
                PushNotificationRequestEvent.PIX_CREDIT_REFUND_DONE,
                PushNotificationRequestEvent.PIX_CREDIT_REFUND_REFUSED]
    }

    private List<PushNotificationRequestEvent> listMobilePhoneRechargeLegacyEvents() {
        return [PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_PENDING,
                PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_CANCELLED,
                PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_CONFIRMED,
                PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_REFUNDED]
    }

    private List<PushNotificationRequestEvent> listAccountStatusLegacyEvents() {
        return [PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_APPROVED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_AWAITING_APPROVAL,
                PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_PENDING,
                PushNotificationRequestEvent.ACCOUNT_STATUS_BANK_ACCOUNT_INFO_REJECTED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_APPROVED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_AWAITING_APPROVAL,
                PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_PENDING,
                PushNotificationRequestEvent.ACCOUNT_STATUS_COMMERCIAL_INFO_REJECTED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_APPROVED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_AWAITING_APPROVAL,
                PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_PENDING,
                PushNotificationRequestEvent.ACCOUNT_STATUS_DOCUMENT_REJECTED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_APPROVED,
                PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_AWAITING_APPROVAL,
                PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_PENDING,
                PushNotificationRequestEvent.ACCOUNT_STATUS_GENERAL_APPROVAL_REJECTED]
    }
}
