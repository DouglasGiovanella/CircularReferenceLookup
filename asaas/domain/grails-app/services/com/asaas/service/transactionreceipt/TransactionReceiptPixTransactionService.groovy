package com.asaas.service.transactionreceipt

import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.domain.transactionreceipt.TransactionReceiptPixTransaction
import com.asaas.exception.BusinessException
import com.asaas.redis.RedissonProxy
import com.asaas.transactionreceipt.TransactionReceiptType
import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class TransactionReceiptPixTransactionService {

    def transactionReceiptService

    public TransactionReceipt processOnDemand(String pixTransactionPublicId) {
        PixTransaction pixTransaction = PixTransaction.query([publicId: pixTransactionPublicId, readOnly: true]).get()
        if (!pixTransaction || !pixTransaction.status.isDone()) throw new BusinessException("Transação Pix não encontrada")

        TransactionReceiptPixTransaction transactionReceiptPixTransaction = TransactionReceiptPixTransaction.query([pixTransactionId: pixTransaction.id]).get()
        TransactionReceipt transactionReceipt

        if (transactionReceiptPixTransaction && transactionReceiptPixTransaction.transactionReceipt) {
            transactionReceipt = transactionReceiptPixTransaction.transactionReceipt
        } else {
            final Long leaseTimeInSeconds = 3
            final Long waitTimeInSeconds = 0.1
            final String key = "lock:TransactionReceiptPixTransaction:${pixTransaction.id}"
            RedissonProxy.instance.lock(key, waitTimeInSeconds, leaseTimeInSeconds, TimeUnit.SECONDS)

            transactionReceipt = transactionReceiptService.save(pixTransaction, TransactionReceiptType.PIX_TRANSACTION_DONE)

            RedissonProxy.instance.unlock(key)
        }

        return transactionReceipt
    }
}
