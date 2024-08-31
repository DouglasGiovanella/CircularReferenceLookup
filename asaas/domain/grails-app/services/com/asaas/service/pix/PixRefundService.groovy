package com.asaas.service.pix

import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class PixRefundService {

    def paymentRefundService
    def pixCreditService
    def pixTransactionService

    public PixTransaction refundCredit(PixTransaction transactionToRefund, BigDecimal refundValue, String description, Map tokenParams) {
        if (transactionToRefund.payment) {
            return refundPayment(transactionToRefund, refundValue, description, tokenParams)
        } else {
            return refundWithoutPayment(transactionToRefund, refundValue, description, tokenParams)
        }
    }

    public void onCriticalActionAuthorization(CriticalAction criticalAction) {
        if (criticalAction.synchronous && !criticalAction.pixTransaction) return

        PixTransaction pixTransaction = criticalAction.pixTransaction

        if (!pixTransaction.type.isCreditRefund()) throw new RuntimeException("A transação Pix [${pixTransaction.id}] não permite autorização.")
        if (!pixTransaction.status.isAwaitingAuthorization()) throw new RuntimeException("A Transação Pix [${pixTransaction.id}] não está aguardando autorização.")

        pixTransactionService.setAsAwaitingRequest(pixTransaction)
        if (pixTransaction.payment) {
            PaymentRefund paymentRefund = PaymentRefund.query([pixTransaction: pixTransaction]).get()
            paymentRefundService.onCriticalActionAuthorization(paymentRefund)
        }
    }

    public void onCriticalActionCancellation(CriticalAction criticalAction) {
        if (criticalAction.synchronous && !criticalAction.pixTransaction) return

        PixTransaction pixTransaction = criticalAction.pixTransaction

        if (!pixTransaction.type.isCreditRefund()) throw new RuntimeException("O tipo da transação Pix [${pixTransaction.id}] não permite cancelamento.")
        if (!pixTransaction.status.isAwaitingAuthorization()) throw new RuntimeException("A Transação Pix [${pixTransaction.id}] não está aguardando autorização.")

        pixTransaction = pixTransactionService.cancel(pixTransaction)
        if (pixTransaction.hasErrors()) throw new RuntimeException("Não foi possível cancelar a transação Pix [${pixTransaction.id}].")
    }

    private PixTransaction refundPayment(PixTransaction transactionToRefund, BigDecimal refundValue, String description, Map tokenParams) {
        Map parsedParams = [
            value: refundValue,
            description: description
        ] + tokenParams
        Payment payment = paymentRefundService.executeRefundRequestedByProvider(transactionToRefund.payment.id, transactionToRefund.customer, parsedParams)

        if (payment.hasErrors()) return DomainUtils.copyAllErrorsFromObject(payment, new PixTransaction())

        return PixTransactionRefund.query([column: "transaction", transactionPaymentId: payment.id, sort: "id", order: "desc"]).get()
    }

    private PixTransaction refundWithoutPayment(PixTransaction transactionToRefund, BigDecimal refundValue, String description, Map tokenParams) {
        return pixCreditService.refundWithoutPayment(
            transactionToRefund,
            refundValue,
            PixTransactionRefundReason.getDefaultReason(),
            description,
            false,
            tokenParams
        )
    }
}
