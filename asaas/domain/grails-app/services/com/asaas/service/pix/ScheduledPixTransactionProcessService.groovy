package com.asaas.service.pix

import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transfer.Transfer
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.adapter.addresskey.AddressKeyAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class ScheduledPixTransactionProcessService {

    def customerAlertNotificationService
    def mobilePushNotificationService
    def pixAddressKeyManagerService
    def pixTransactionService

    public void process() {
        List<Long> scheduledPixTransactionIdList = PixTransaction.query([column: "id", scheduledDate: new Date(), status: PixTransactionStatus.SCHEDULED]).list(max: 50)

        for (Long scheduledPixTransactionId : scheduledPixTransactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(scheduledPixTransactionId)

                processScheduledPixTransaction(pixTransaction)
            }, [logErrorMessage: "ScheduledPixTransactionProcessService.process -> Erro ao processar PixTrasanction agendado. [${scheduledPixTransactionId}]"])
        }
    }

    public void processScheduledPixTransaction(PixTransaction pixTransaction) {
        PixTransaction validatedTransaction = PixCheckoutValidator.validateScheduledPixTransactionProcessing(pixTransaction)
        if (validatedTransaction.hasErrors()) {
            refuse(pixTransaction, validatedTransaction.errors.allErrors.first().defaultMessage)
            return
        }

        if (!pixTransaction.originType.isManual()) {
            AddressKeyAdapter addressKeyAdapter = pixAddressKeyManagerService.findExternallyPixAddressKey(pixTransaction.externalAccount.pixKey, null, pixTransaction.customer)

            BusinessValidation validatedBusiness = PixCheckoutValidator.validateAddressKeyOwnership(pixTransaction.externalAccount.cpfCnpj, addressKeyAdapter)
            if (!validatedBusiness.isValid()) {
                refuse(pixTransaction, validatedBusiness.getFirstErrorMessage())
                return
            }

            pixTransaction.endToEndIdentifier = addressKeyAdapter.endToEndIdentifier
        }

        Boolean shouldChargeDebitFee = CustomerCheckoutFee.shouldChargePixDebitFee(pixTransaction)
        pixTransaction = pixTransactionService.setAsAwaitingRequest(pixTransaction)
        pixTransactionService.createDebitFinancialTransaction(pixTransaction, shouldChargeDebitFee)
    }

    private void refuse(PixTransaction pixTransaction, String refusalReasonDescription) {
        pixTransactionService.setAsRefused(pixTransaction, PixTransactionRefusalReason.SCHEDULED_TRANSACTION_VALIDATE_ERROR, refusalReasonDescription, null)

        String transferPublicId = Transfer.query([column: 'publicId', pixTransaction: pixTransaction]).get()
        customerAlertNotificationService.notifyScheduledPixDebitProcessingRefused(pixTransaction, transferPublicId)
        mobilePushNotificationService.notifyScheduledPixDebitProcessingRefused(pixTransaction, transferPublicId)
    }
}
