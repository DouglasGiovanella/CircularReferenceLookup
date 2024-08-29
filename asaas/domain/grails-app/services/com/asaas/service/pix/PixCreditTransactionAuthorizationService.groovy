package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.exception.BusinessException

import grails.transaction.Transactional

@Transactional
class PixCreditTransactionAuthorizationService {

    def paymentConfirmService
    def paymentRefundService
    def pixTransactionNotificationService
    def pixTransactionService

    public void onCashInRiskAnalysisRequestApproved(PixTransaction pixTransaction) {
        if (!pixTransaction.status.isAwaitingCashInRiskAnalysisRequest()) throw new BusinessException("${this.getClass().getSimpleName()}.onCashInRiskAnalysisRequestApproved > Transação Pix não está aguardando análise de bloqueio cautelar [pixTransactionId: ${pixTransaction.id}, pixTransactionStatus: ${pixTransaction.status}].")
        if (pixTransaction.payment.creditDate < new Date().clearTime()) throw new BusinessException("${this.getClass().getSimpleName()}.onCashInRiskAnalysisRequestApproved > Data de liquidação da cobrança é anterior a hoje [paymentId: ${pixTransaction.paymentId}, paymentStatus: ${pixTransaction.payment.creditDate}].")

        paymentConfirmService.executePaymentCredit(pixTransaction.payment)
        pixTransactionService.executeRoutinesPostCreditReceived(pixTransaction)
    }

    public void onCashInRiskAnalysisRequestDenied(PixTransaction pixTransaction) {
        if (!pixTransaction.status.isAwaitingCashInRiskAnalysisRequest()) throw new RuntimeException("${this.getClass().getSimpleName()}.onCashinRiskAnalysisRequestDenied > Transação Pix não está aguardando análise de bloqueio cautelar [pixTransactionId: ${pixTransaction.id}, pixTransactionStatus: ${pixTransaction.status}].")

        Map options = [reason: PixTransactionRefundReason.FRAUD, bypassCustomerValidation: true]
        paymentRefundService.refund(pixTransaction.payment, options)
        pixTransactionNotificationService.sendPixCashInRiskAnalysisRefundedNotification(pixTransaction)
    }
}
