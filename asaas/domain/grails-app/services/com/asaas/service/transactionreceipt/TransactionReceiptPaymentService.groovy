package com.asaas.service.transactionreceipt

import com.asaas.domain.payment.Payment
import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.exception.BusinessException
import com.asaas.redis.RedissonProxy
import com.asaas.transactionreceipt.TransactionReceiptType
import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class TransactionReceiptPaymentService {

    def transactionReceiptService

    public TransactionReceipt processOnDemandPaymentDeleted(String paymentPublicId) {
        Payment payment = Payment.query([publicId: paymentPublicId, deleted: true]).get()
         if (!payment) throw new BusinessException("Pagamento não encontrado")

        final Long leaseTimeInSeconds = 3
        final Long waitTimeInSeconds = 0.1
        final String key = "lock:TransactionReceiptPayment:${payment.id}"

        TransactionReceipt transactionReceipt = TransactionReceipt.query([paymentId: payment.id, type: TransactionReceiptType.PAYMENT_DELETED]).get()

        if (!transactionReceipt) {
            RedissonProxy.instance.lock(key, waitTimeInSeconds, leaseTimeInSeconds, TimeUnit.SECONDS)

            transactionReceipt = transactionReceiptService.save(payment, TransactionReceiptType.PAYMENT_DELETED)

            RedissonProxy.instance.unlock(key)
        }

        return transactionReceipt
    }
}
