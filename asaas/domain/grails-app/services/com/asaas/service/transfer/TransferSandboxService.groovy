package com.asaas.service.transfer

import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.transfer.Transfer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class TransferSandboxService {

    def creditTransferRequestService
    def pixTransactionService

    public CreditTransferRequest confirmTed(Transfer transfer) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Não é possível executar a operação TransferSandboxService.confirmTed no ambiente de produção")

        BusinessValidation validatedTransfer = validateConfirmTed(transfer)
        if (!validatedTransfer.isValid()) throw new BusinessException(validatedTransfer.getFirstErrorMessage())

        return creditTransferRequestService.confirm(transfer.creditTransferRequest.id)
    }

    public BusinessValidation validateConfirmTed(Transfer transfer) {
        BusinessValidation validatedTransfer = new BusinessValidation()

        if (!transfer.type.isTed()) {
            validatedTransfer.addError("transferSandbox.error.cannotBeConfirmed.isNotTed")
            return validatedTransfer
        }

        if (transfer.creditTransferRequest.isScheduledTransferNotProcessed()) {
            validatedTransfer.addError("transferSandbox.error.cannotBeConfirmed.isScheduled")
            return validatedTransfer
        }

        return creditTransferRequestService.validateConfirm(transfer.creditTransferRequest)
    }

    public void refuse(Transfer transfer) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Não é possível executar a operação TransferSandboxService.refuse no ambiente de produção")

        BusinessValidation validatedTransfer = validateRefuse(transfer)
        if (!validatedTransfer.isValid()) throw new BusinessException(validatedTransfer.getFirstErrorMessage())

        if (transfer.type.isTed()) {
            creditTransferRequestService.setAsFailed(transfer.creditTransferRequest)
        } else if (transfer.type.isPix()) {
            pixTransactionService.refuse(transfer.pixTransaction, PixTransactionRefusalReason.ERROR, "Simulação de falha em Sandbox", null)
        }
    }

    public BusinessValidation validateRefuse(Transfer transfer) {
        BusinessValidation validatedTransfer = new BusinessValidation()

        if (transfer.type.isPix()) {
            return pixTransactionService.validateRefuse(transfer.pixTransaction)
        } else if (transfer.type.isTed()) {
            return creditTransferRequestService.canBeSetAsFailed(transfer.creditTransferRequest)
        } else {
            validatedTransfer.addError("transferSandbox.error.cannotBeRefused.invalidType")
        }

        return validatedTransfer
    }

}
