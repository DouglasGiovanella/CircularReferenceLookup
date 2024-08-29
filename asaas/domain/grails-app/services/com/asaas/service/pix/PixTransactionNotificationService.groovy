package com.asaas.service.pix

import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transfer.Transfer
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionNotificationService {

    def checkoutNotificationService
    def customerAlertNotificationService
    def mobilePushNotificationService
    def pushNotificationRequestPixEventService

    public void sendConclusionNotification(PixTransaction transaction) {
        String transferPublicId = Transfer.query([column: 'publicId', pixTransaction: transaction]).get()

        switch (transaction.type) {
            case [PixTransactionType.DEBIT, PixTransactionType.DEBIT_REFUND_CANCELLATION]:
                customerAlertNotificationService.notifyPixDebitDone(transaction, transferPublicId)
                if (transaction.originType.isQrCode()) {
                    Boolean hasAsaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.query([exists: true, destinationPixTransaction: transaction]).get().asBoolean()
                    if (hasAsaasMoneyTransactionInfo) break
                }
                mobilePushNotificationService.notifyPixDebitDone(transaction, transferPublicId)
                if (transaction.type.isDebit()) checkoutNotificationService.saveAsyncActionToSendPixTransactionReceipt(transaction)
                break
            case PixTransactionType.DEBIT_REFUND:
                pushNotificationRequestPixEventService.saveTransaction(transaction, PushNotificationRequestEvent.PIX_DEBIT_REFUNDED)
                customerAlertNotificationService.notifyPixDebitRefundReceived(transaction, transferPublicId)

                Boolean hasAsaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.query([exists: true, destinationPixTransaction: transaction]).get().asBoolean()
                if (hasAsaasMoneyTransactionInfo) break

                mobilePushNotificationService.notifyPixDebitRefundReceived(transaction, transferPublicId)
                break
            case PixTransactionType.CREDIT:
                customerAlertNotificationService.notifyPixCreditReceived(transaction)

                if (!transaction.payment) {
                    pushNotificationRequestPixEventService.saveTransaction(transaction, PushNotificationRequestEvent.PIX_CREDIT_RECEIVED)
                } else if (!transaction.originType.isDynamicQrCode()) {
                    mobilePushNotificationService.notifyPixCreditReceived(transaction)
                }
                break
            case PixTransactionType.CREDIT_REFUND:
                if (!transaction.payment) pushNotificationRequestPixEventService.saveTransaction(transaction, PushNotificationRequestEvent.PIX_CREDIT_REFUND_DONE)

                customerAlertNotificationService.notifyPixCreditRefundDone(transaction, transferPublicId)
                mobilePushNotificationService.notifyPixCreditRefundDone(transaction, transferPublicId)
                break
        }
    }

    public void sendAwaitingCashInRiskAnalysisNotification(PixTransaction transaction) {
        customerAlertNotificationService.notifyPixAwaitingCashInRiskAnalysis(transaction)
        mobilePushNotificationService.notifyPixAwaitingCashInRiskAnalysis(transaction)
    }

    public void sendAwaitingInstantPaymentAccountBalanceNotification(PixTransaction pixTransaction) {
        Long transferId = Transfer.query([readOnly: true, column: "id", pixTransaction: pixTransaction]).get() as Long

        customerAlertNotificationService.notifyPixAwaitingInstantPaymentAccountBalance(pixTransaction, transferId)
    }

    public void sendPixCashInRiskAnalysisRefundedNotification(PixTransaction transaction) {
        customerAlertNotificationService.notifyPixCashInRiskAnalysisRefunded(transaction)
        mobilePushNotificationService.notifyPixCashInRiskAnalysisRefunded(transaction)
    }

    public void sendCreditRefundRefusedNotification(PixTransaction pixTransaction, PixTransactionRefusalReason refusalReason) {
        String transferPublicId = Transfer.query([column: 'publicId', pixTransaction: pixTransaction]).get()

        String message = Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason.toString()}")
        customerAlertNotificationService.notifyPixCreditRefundRefused(pixTransaction, message, transferPublicId)
        mobilePushNotificationService.notifyPixCreditRefundRefused(pixTransaction, message, transferPublicId)
    }

    public void sendDebitRefusedNotification(PixTransaction pixTransaction, PixTransactionRefusalReason refusalReason) {
        String transferPublicId = Transfer.query([column: 'publicId', pixTransaction: pixTransaction]).get()

        String message = Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason.toString()}")
        customerAlertNotificationService.notifyPixDebitRefused(pixTransaction, message, transferPublicId)
        mobilePushNotificationService.notifyPixDebitRefused(pixTransaction, message, transferPublicId)
    }

}
