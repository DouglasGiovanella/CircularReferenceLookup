package com.asaas.service.transfer

import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transfer.Transfer
import com.asaas.domain.transfer.TransferOriginRequester
import com.asaas.domain.transfer.TransferType
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixTransactionStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.transfer.TransferStatus
import grails.transaction.Transactional

@Transactional
class TransferService {

    def asaasSecurityMailMessageService
    def pushNotificationRequestTransferService
    def receivableAnticipationValidationService
    def transferDestinationBankAccountService
    def originRequesterInfoService

    public Transfer save(Customer customer, InternalTransfer internalTransfer) {
        if (!internalTransfer) throw new RuntimeException("Um InternalTransfer deve ser informado.")

        Transfer transfer = build(customer)
        transfer.status = TransferStatus.PENDING
        transfer.internalTransfer = internalTransfer
        transfer.type = TransferType.INTERNAL
        transfer.save(failOnError: true)

        transfer.destinationBankAccount = transferDestinationBankAccountService.saveFromInternalTransfer(transfer)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_CREATED, transfer)
        originRequesterInfoService.save(transfer)

        return transfer
    }

    public Transfer save(Customer customer, CreditTransferRequest creditTransferRequest) {
        if (!creditTransferRequest) throw new RuntimeException("Um CreditTransferRequest deve ser informado.")

        Transfer transfer = build(customer)
        transfer.status = creditTransferRequest.isScheduledTransferNotProcessed() ? TransferStatus.SCHEDULED : TransferStatus.PENDING
        transfer.creditTransferRequest = creditTransferRequest
        transfer.type = TransferType.TED
        transfer.save(failOnError: true)

        transfer.destinationBankAccount = transferDestinationBankAccountService.saveFromCreditTransferRequest(transfer)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_CREATED, transfer)
        originRequesterInfoService.save(transfer)

        return transfer
    }

    public Transfer save(PixTransaction pixTransaction) {
        if (!pixTransaction) throw new RuntimeException("Um PixTransaction deve ser informado.")

        Transfer transfer = build(pixTransaction.customer)
        transfer.status = PixUtils.isScheduledTransaction(pixTransaction.scheduledDate) ? TransferStatus.SCHEDULED : TransferStatus.PENDING
        transfer.pixTransaction = pixTransaction
        transfer.type = TransferType.PIX
        transfer.save(failOnError: true)

        transfer.destinationBankAccount = transferDestinationBankAccountService.saveFromPixTransaction(transfer)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_CREATED, transfer)

        TransferOriginRequester transferOriginRequester = originRequesterInfoService.save(transfer)
        if (transferOriginRequester.device && !transferOriginRequester.device.trustedToCheckout) {
            Boolean shouldNotifyCheckoutOnUntrustedDevice = true
            if (pixTransaction.type.isCreditRefund()) {
                Boolean refundedTransactionIsAwaitingCashInRiskAnalysis = pixTransaction.getRefundedTransaction().isAwaitingCashInRiskAnalysis()
                if (refundedTransactionIsAwaitingCashInRiskAnalysis) shouldNotifyCheckoutOnUntrustedDevice = false
            }

            if (shouldNotifyCheckoutOnUntrustedDevice) asaasSecurityMailMessageService.notifyCheckoutOnUntrustedDevice(transferOriginRequester.user.customer, transfer)
        }

        return transfer
    }

    public void setAsPending(Transfer transfer) {
        setStatus(transfer, TransferStatus.PENDING)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_PENDING, transfer)
    }

    public void setAsBankProcessing(Transfer transfer) {
        setStatus(transfer, TransferStatus.BANK_PROCESSING)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_IN_BANK_PROCESSING, transfer)
    }

    public void setAsDone(Transfer transfer, Date transferDate) {
        if (transfer.status == TransferStatus.DONE) throw new BusinessException("Não é possível alterar o status de uma transferência já concluída.")

        transfer.transferDate = transferDate
        setStatus(transfer, TransferStatus.DONE)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_DONE, transfer)
        Boolean hasAlreadyConfirmedTransferToAnticipation = Transfer.confirmedToAnticipation([exists: true, "id[ne]": transfer.id, customer: transfer.customer]).get().asBoolean()
        if (!hasAlreadyConfirmedTransferToAnticipation) receivableAnticipationValidationService.onLegalPersonHasFirstTransferConfirmed(transfer.customer)
    }

    public void setAsBlocked(Transfer transfer) {
        setStatus(transfer, TransferStatus.BLOCKED)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_BLOCKED, transfer)
    }

    public void setAsCancelled(Transfer transfer) {
        setStatus(transfer, TransferStatus.CANCELLED)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_CANCELLED, transfer)
    }

    public void setAsFailed(Transfer transfer) {
        setStatus(transfer, TransferStatus.FAILED)

        pushNotificationRequestTransferService.save(PushNotificationRequestEvent.TRANSFER_FAILED, transfer)
    }

    public void updateStatusIfNecessary(PixTransaction pixTransaction) {
        Transfer transfer = Transfer.query([pixTransaction: pixTransaction]).get()
        if (!transfer) return

        PixTransactionStatus pixTransactionStatus = pixTransaction.status

        if (pixTransactionStatus.isEquivalentToPending()) {
            setAsPending(transfer)
        } else if (pixTransactionStatus.isDone()) {
            setAsDone(transfer, pixTransaction.effectiveDate)
        } else if (pixTransactionStatus.isRefused()) {
            setAsFailed(transfer)
        } else if (pixTransactionStatus.isCancelled()) {
            setAsCancelled(transfer)
        } else {
            throw new RuntimeException("TransferService.updateStatusIfNecessary -> Status não mapeado para ${pixTransactionStatus.toString()} [transfer: ${transfer.id}]")
        }
    }

    private void setStatus(Transfer transfer, TransferStatus status) {
        transfer.status = status
        transfer.save(failOnError: true)
    }

    private Transfer build(Customer customer) {
        Transfer transfer = new Transfer()

        transfer.customer = customer
        transfer.publicId = UUID.randomUUID()

        return transfer
    }
}
