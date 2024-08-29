package com.asaas.service.bankaccounttransfer

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transfer.Transfer
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BankAccountTransferService {

    def creditTransferRequestService
    def internalTransferService
    def pixTransactionService

    public Transfer cancel(Transfer transfer) {
        if (transfer.type.isTed()) {
            creditTransferRequestService.cancel(transfer.creditTransferRequest)
        } else if (transfer.type.isPix()) {
            PixTransaction pixTransaction = pixTransactionService.cancel(transfer.pixTransaction)

            if (pixTransaction.hasErrors()) throw new BusinessException(Utils.getMessageProperty(pixTransaction.errors.allErrors.first()))
        } else if (transfer.type.isInternal()) {
            internalTransferService.cancel(transfer.internalTransfer)
        }

        return transfer
    }
}
