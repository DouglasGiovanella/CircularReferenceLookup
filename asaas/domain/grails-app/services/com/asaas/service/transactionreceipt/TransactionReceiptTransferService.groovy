package com.asaas.service.transactionreceipt

import com.asaas.domain.transfer.Transfer
import com.asaas.generatereceipt.PixTransactionGenerateReceiptUrl
import grails.transaction.Transactional

@Transactional
class TransactionReceiptTransferService {

    def linkService

    public String generateTransactionReceiptUrl(Transfer transfer) {
        String transactionReceiptUrl

        if (transfer.internalTransfer) {
            transactionReceiptUrl = linkService.viewTransactionReceipt(transfer.internalTransfer.getTransactionReceiptPublicId(), true)
        } else if (transfer.pixTransaction) {
            transactionReceiptUrl = new PixTransactionGenerateReceiptUrl(transfer.pixTransaction).generateAbsoluteUrl()
        } else {
            transactionReceiptUrl = linkService.viewTransactionReceipt(transfer.creditTransferRequest.getTransactionReceiptPublicId(), true)
        }

        return transactionReceiptUrl
    }
}
