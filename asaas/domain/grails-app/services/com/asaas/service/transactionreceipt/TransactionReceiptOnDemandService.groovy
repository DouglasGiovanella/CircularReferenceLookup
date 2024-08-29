package com.asaas.service.transactionreceipt

import com.asaas.domain.transactionreceipt.TransactionReceipt
import com.asaas.transactionreceipt.TransactionReceiptType
import com.asaas.utils.TransactionReceiptUtils
import grails.transaction.Transactional

@Transactional
class TransactionReceiptOnDemandService {

    def transactionReceiptPixTransactionService
    def transactionReceiptPaymentService

    public TransactionReceipt processTransactionReceiptOnDemand(String hash) {
        Map hashDecoded = TransactionReceiptUtils.decodedHashForGenerateOnDemand(hash)
        TransactionReceiptType transactionReceiptType = hashDecoded.transactionReceiptType
        String objectPublicId = hashDecoded.objectPublicId
        TransactionReceipt transactionReceipt

        switch (transactionReceiptType) {
            case TransactionReceiptType.PIX_TRANSACTION_DONE:
                transactionReceipt = transactionReceiptPixTransactionService.processOnDemand(objectPublicId)
                break
            case TransactionReceiptType.PAYMENT_DELETED:
                transactionReceipt = transactionReceiptPaymentService.processOnDemandPaymentDeleted(objectPublicId)
                break
            default:
                throw new RuntimeException("O tipo do comprovante é inválido.")
        }

        return transactionReceipt
    }
}
