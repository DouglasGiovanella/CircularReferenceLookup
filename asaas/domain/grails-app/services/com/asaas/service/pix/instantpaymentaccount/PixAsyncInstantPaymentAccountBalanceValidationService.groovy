package com.asaas.service.pix.instantpaymentaccount

import com.asaas.domain.pix.PixAsyncInstantPaymentAccountBalanceValidation
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.adapter.instantpaymentaccount.PixInstantPaymentAccountBalanceAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixAsyncInstantPaymentAccountBalanceValidationService {

    def pixInstantPaymentAccountCacheService
    def pixInstantPaymentAccountService
    def pixTransactionNotificationService
    def pixTransactionService

    public void saveIfNecessary(PixTransaction pixTransaction) {
        Boolean hasBeenValidated = PixAsyncInstantPaymentAccountBalanceValidation.query([exists: true, pixTransaction: pixTransaction]).get().asBoolean()
        if (hasBeenValidated) return

        PixAsyncInstantPaymentAccountBalanceValidation validation = new PixAsyncInstantPaymentAccountBalanceValidation()
        validation.pixTransaction = pixTransaction
        validation.save(failOnError: true)

        pixTransactionNotificationService.sendAwaitingInstantPaymentAccountBalanceNotification(pixTransaction)
    }

    public void processPixTransaction() {
        PixInstantPaymentAccountBalanceAdapter balanceAdapter = pixInstantPaymentAccountCacheService.getBalance()
        if (!balanceAdapter) {
            AsaasLogger.warn("PixAsyncInstantPaymentAccountBalanceValidationService.processPixTransaction >> Não foi possível obter o saldo da Conta PI")
            return
        }

        final Integer limitOfItems = 100
        List<Long> pixTransactionIdList = PixTransaction.awaitingInstantPaymentAccountBalance(["column": "id", disableSort: true]).list(max: limitOfItems) as List<Long>

        for (Long pixTransactionId : pixTransactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(pixTransactionId)

                if (!pixTransaction.status.isAwaitingInstantPaymentAccountBalance()) return

                if (!pixInstantPaymentAccountService.hasEnoughBalanceForCheckout(pixTransaction)) return

                pixTransactionService.setAsAwaitingRequest(pixTransaction)
            }, [logErrorMessage: "PixAsyncInstantPaymentAccountBalanceValidationService.processPixTransaction >> Não foi possível validar se há saldo da Conta PI [pixTransaction.id: ${pixTransactionId}]"])
        }
    }

    public void refusePixTransactionAfterMaximumBlockedTimeReached() {
        Date maximumBlockedDate = CustomDateUtils.sumMinutes(new Date(), PixAsyncInstantPaymentAccountBalanceValidation.MAXIMUM_BLOCKED_MINUTES * -1)

        final Integer limitOfItems = 100
        Map search = [
            column: "pixTransaction.id",
            "dateCreated[lt]": maximumBlockedDate,
            pixTransactionStatus: PixTransactionStatus.AWAITING_INSTANT_PAYMENT_ACCOUNT_BALANCE
        ]

        List<Long> pixTransactionIdList = PixAsyncInstantPaymentAccountBalanceValidation.query(search).list(max: limitOfItems) as List<Long>

        for (Long pixTransactionId : pixTransactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(pixTransactionId)
                refuseForInsufficientInstantPaymentAccountBalance(pixTransaction)
            }, [logErrorMessage: "PixAsyncInstantPaymentAccountBalanceValidationService.refusePixTransactionAfterMaximumBlockedTimeReached >> Não foi possível recusar a transação [pixTransaction.id: ${pixTransactionId}]"])
        }
    }

    private void refuseForInsufficientInstantPaymentAccountBalance(PixTransaction pixTransaction) {
        if (!pixTransaction.status.isAwaitingInstantPaymentAccountBalance()) return

        PixTransactionRefusalReason refusalReason = PixTransactionRefusalReason.NO_BALANCE_PI_ACCOUNT
        String refusalReasonDescription = Utils.getMessageProperty("PixTransactionRefusalReason.${refusalReason.toString()}")

        pixTransactionService.refuse(pixTransaction, refusalReason, refusalReasonDescription, null)
    }
}
