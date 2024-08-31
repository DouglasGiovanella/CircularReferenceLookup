package com.asaas.service.pix.pixTransactionRefund

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixTransactionRefundReason

import grails.transaction.Transactional

@Transactional
class PixTransactionRefundService {

    public PixTransactionRefund save(PixTransaction refundedTransaction, PixTransaction refund, PixTransactionRefundReason reason, String reasonDescription) {
        PixTransactionRefund transactionRefund = new PixTransactionRefund()
        transactionRefund.refundedTransaction = refundedTransaction
        transactionRefund.transaction = refund
        transactionRefund.reason = reason
        transactionRefund.reasonDescription = PixUtils.sanitizeHtml(reasonDescription)
        transactionRefund.save(failOnError: true, flush: true)

        return transactionRefund
    }
}
