package com.asaas.service.pix

import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.pix.PixTransaction
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixDebitAuthorizationService {

    def pixTransactionService
    def scheduledPixTransactionProcessService

    public void onCriticalActionAuthorization(CriticalAction criticalAction) {
        PixTransaction pixTransaction = pixTransactionService.onDebitAuthorization(criticalAction.pixTransaction)
        processScheduledIfNecessary(pixTransaction)
    }

    public void onCriticalActionCancellation(CriticalAction criticalAction) {
        PixTransaction pixTransaction = criticalAction.pixTransaction
        if (pixTransaction.status.isRefused()) return
        if (!pixTransaction.status.isAwaitingCriticalActionAuthorization()) throw new RuntimeException("${this.getClass().getSimpleName()}.onCriticalActionCancellation > Transação [${pixTransaction.id}] não está aguardando autorização crítica.")

        pixTransactionService.cancel(pixTransaction)
    }

    public void onCriticalActionAuthorizationExpiration(CriticalAction criticalAction) {
        PixTransaction pixTransaction = criticalAction.pixTransaction

        if (!pixTransaction.status.isAwaitingCriticalActionAuthorization()) throw new RuntimeException("${this.getClass().getSimpleName()}.onCriticalActionAuthorizationExpiration > Transação [${pixTransaction.id}] não está aguardando autorização crítica.")
        if (!pixTransaction.scheduledDate) throw new RuntimeException("${this.getClass().getSimpleName()}.onCriticalActionAuthorizationExpiration > Transação [${pixTransaction.id}] não é agendada")

        pixTransactionService.setAsRefused(pixTransaction, PixTransactionRefusalReason.SCHEDULED_TRANSACTION_VALIDATE_ERROR, "Autorização ultrapassou a data limite para a transação agendada", null)
    }

    public void onCheckoutRiskAnalysisRequestApproved(Long pixTransactionId) {
        PixTransaction transaction = PixTransaction.get(pixTransactionId)
        if (!transaction) return
        if (transaction.status.isCancelled() || transaction.status.isRefused()) return
        if (!transaction.status.isAwaitingCheckoutRiskAnalysisRequest()) throw new RuntimeException("${this.getClass().getSimpleName()}.onCheckoutRiskAnalysisRequestApproved > Transação [${transaction.id}] não está aguardando análise de saque.")

        pixTransactionService.onDebitAuthorization(transaction)

        processScheduledIfNecessary(transaction)
    }

    public void onCheckoutRiskAnalysisRequestDenied(Long pixTransactionId, PixTransactionRefusalReason reason) {
        PixTransaction transaction = PixTransaction.get(pixTransactionId)
        if (!transaction) return
        if (transaction.status.isCancelled() || transaction.status.isRefused()) return
        if (!transaction.status.isAwaitingCheckoutRiskAnalysisRequest()) throw new RuntimeException("${this.getClass().getSimpleName()}.onCheckoutRiskAnalysisRequestDenied > Transação [${transaction.id}] não está aguardando análise de saque.")

        String refusalDescription = Utils.getMessageProperty("PixTransactionRefusalReason.${reason.toString()}")
        if (transaction.scheduledDate) {
            pixTransactionService.setAsRefused(transaction, reason, refusalDescription, null)
        } else {
            pixTransactionService.refuse(transaction, reason, refusalDescription, null)
        }
    }

    public void onExternalAuthorizationApproved(PixTransaction transaction) {
        if (!transaction.status.isAwaitingExternalAuthorization()) throw new RuntimeException("${this.getClass().getSimpleName()}.onExternalAuthorizationApproved > Transação [${transaction.id}] não está aguardando autorização externa.")

        pixTransactionService.onDebitAuthorization(transaction)
    }

    public void onExternalAuthorizationRefused(PixTransaction transaction) {
        if (!transaction.status.isAwaitingExternalAuthorization()) throw new RuntimeException("${this.getClass().getSimpleName()}.onExternalAuthorizationRefused > Transação [${transaction.id}] não está aguardando autorização externa.")

        PixTransactionRefusalReason reason = PixTransactionRefusalReason.EXTERNAL_AUTHORIZATION_REFUSED
        String refusalDescription = Utils.getMessageProperty("PixTransactionRefusalReason.${reason.toString()}")
        pixTransactionService.refuse(transaction, reason, refusalDescription, null)
    }

    public BusinessValidation validateExternalAuthorizationTransferStatus(PixTransaction transaction) {
        BusinessValidation businesValidation = new BusinessValidation()

        if (transaction.status.isCancelled()) businesValidation.addError("customerExternalAuthorization.transfer.pixTransaction.status.isCancelled")

        return businesValidation
    }

    private void processScheduledIfNecessary(PixTransaction pixTransaction) {
        if (!pixTransaction.status.isScheduled()) return

        if (pixTransaction.scheduledDate == new Date().clearTime()) {
            scheduledPixTransactionProcessService.processScheduledPixTransaction(pixTransaction)
        }
    }
}
