package com.asaas.service.pix

import com.asaas.domain.financialtransactionpixtransaction.FinancialTransactionPixTransaction
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAsaasIdempotencyKeyType
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionSynchronizationService {

    def pixAsaasIdempotencyKeyService
    def pixInstantPaymentAccountService
    def pixTransactionErrorLogService
    def pixTransactionManagerService
    def pixTransactionProcessingRetryService
    def pixTransactionService

    public void synchronizeAwaitingRequestDebitTransactions() {
        final Integer maxItemsOnList = 120
        List<Long> transactionIdList = PixTransaction.awaitingRequest([column: "id", type: PixTransactionType.DEBIT, disableSort: true, includeDeleted: true]).list(max: maxItemsOnList)
        if (!transactionIdList) return

        final Integer itemsPerThread = 6
        final Integer limitOfThreads = 20
        ThreadUtils.dangerousProcessWithThreadsOnDemand(transactionIdList, itemsPerThread, limitOfThreads, { List<Long> transactionIdListPerThread ->
            processAwaitingRequestDebitOrDebitRefundCancellationTransactions(transactionIdListPerThread)
        })
    }

    public void synchronizeAwaitingRequestDebitRefundCancellationTransactions() {
        final Integer maxItemsOnList = 60
        List<Long> transactionIdList = PixTransaction.awaitingRequest([column: "id", type: PixTransactionType.DEBIT_REFUND_CANCELLATION, disableSort: true]).list(max: maxItemsOnList)
        if (!transactionIdList) return

        processAwaitingRequestDebitOrDebitRefundCancellationTransactions(transactionIdList)
    }

    public void synchronizeAwaitingRequestCreditRefundTransactions() {
        List<Long> transactionIdList = PixTransaction.awaitingRequest([column: "id", type: PixTransactionType.CREDIT_REFUND, disableSort: true]).list()

        for (Long transactionId : transactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(transactionId)

                if (!pixTransaction.status.isAwaitingRequest()) throw new RuntimeException("A transação Pix não está aguardando solicitação")

                if (!pixTransaction.receivedWithAsaasQrCode) {
                    if (blockPixTransactionIfInsufficientInstantPaymentAccountBalance(pixTransaction)) return
                }

                PixAsaasIdempotencyKeyType idempotencyKeyType = PixAsaasIdempotencyKeyType.CREATE_CREDIT_REFUND
                if (!pixAsaasIdempotencyKeyService.hasIdempotencyKey(pixTransaction, idempotencyKeyType)) {
                    pixAsaasIdempotencyKeyService.save(pixTransaction, idempotencyKeyType)
                    return
                }

                requestTransactions(pixTransaction)
            }, [logErrorMessage: "PixTransactionSynchronizationService -> Erro ao criar transação Pix. [pixTransaction.id: ${transactionId}]"])
        }
    }

    public void synchronizeRequestedCreditRefundsReceivedWithAsaasQrCode() {
        List<Long> pixTransactionIdList = PixTransaction.creditRefund([column: "id", status: PixTransactionStatus.REQUESTED, receivedWithAsaasQrCode: true, disableSort: true]).list()

        for (Long pixTransactionId : pixTransactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionRefund refund = PixTransactionRefund.query([transactionId: pixTransactionId]).get()

                Map creditRefundInfo = pixTransactionManagerService.getCreditRefund(refund)
                if (creditRefundInfo.status.isDone()) {
                    pixTransactionService.effectivate(refund.transaction, creditRefundInfo.effectiveDate, creditRefundInfo.endToEndIdentifier)
                } else if (creditRefundInfo.status.isRefused()) {
                    pixTransactionService.refuse(refund.transaction, PixTransactionRefusalReason.DENIED, creditRefundInfo.refusalReason, null)
                }
            }, [logErrorMessage: "PixTransactionSynchronizationService.synchronizeRequestedCreditRefundsReceivedWithAsaasQrCode() -> Erro ao consultar transação Pix. [PixTransaction.id: ${pixTransactionId}]"])
        }
    }

    private void requestTransactions(PixTransaction pixTransaction) {
        Map response
        if (pixTransaction.type.isDebit() || pixTransaction.type.isDebitRefundCancellation()) {
            response = pixTransactionManagerService.createDebit(pixTransaction)
        } else {
            response = pixTransactionManagerService.createCreditRefund(PixTransactionRefund.query([transaction: pixTransaction]).get())
        }

        if (response.success) {
            pixTransactionService.setAsRequested(pixTransaction, response.externalIdentifier, response.endToEndIdentifier)
        } else if (response.withoutExternalResponse) {
            pixTransactionErrorLogService.saveConnectionErrorOnRequest(pixTransaction)
        } else if (response.handleErrorManually) {
            if (!pixTransaction.type.isCreditRefund()) throw new RuntimeException("Erro ao criar transação Pix. Tipo de transação é inválida.")
            AsaasLogger.error("PixTransactionSynchronizationService.requestTransactions() -> ATENÇÃO! Revisar se transação foi efetuada pelo Bradesco. [pixTransaction.id: ${pixTransaction.id}]")

            pixTransactionService.setAsError(pixTransaction)
        } else if (response.unknowError) {
            pixTransactionService.setAsError(pixTransaction)
            pixTransactionProcessingRetryService.save(pixTransaction)
            AsaasLogger.error("PixTransactionSynchronizationService.requestTransactions() -> Erro desconhecido ao efetuar transação. [pixTransaction.id: ${pixTransaction.id}]")
        } else {
            pixTransactionService.refuse(pixTransaction, PixTransactionRefusalReason.ERROR, "Não foi possível efetuar esta transação Pix.", null)
        }
    }

    private void processAwaitingRequestDebitOrDebitRefundCancellationTransactions(List<Long> transactionIdList) {
        for (Long transactionId : transactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(transactionId)

                if (!pixTransaction.status.isAwaitingRequest()) throw new RuntimeException("A transação Pix não está aguardando solicitação")

                if (blockPixTransactionIfInsufficientInstantPaymentAccountBalance(pixTransaction)) return

                pixTransaction = invalidatePixTransactionIfNoFinancialTransaction(pixTransaction)
                if (pixTransaction.status.isError()) return

                PixAsaasIdempotencyKeyType idempotencyKeyType = PixAsaasIdempotencyKeyType.CREATE_DEBIT
                if (!pixAsaasIdempotencyKeyService.hasIdempotencyKey(pixTransaction, idempotencyKeyType)) {
                    pixAsaasIdempotencyKeyService.save(pixTransaction, idempotencyKeyType)
                    return
                }

                requestTransactions(pixTransaction)
            }, [logErrorMessage: "PixTransactionSynchronizationService.processAwaitingRequestDebitOrCreditRefundCancellationTransactions() -> Erro ao criar transação Pix. [pixTransaction.id: ${transactionId}]"])
        }
    }

    private PixTransaction invalidatePixTransactionIfNoFinancialTransaction(PixTransaction pixTransaction) {
        Boolean hasFinancialTransaction = FinancialTransactionPixTransaction.query([pixTransaction: pixTransaction, exists: true]).get().asBoolean()
        if (!hasFinancialTransaction) {
            AsaasLogger.error("PixTransactionSynchronizationService.invalidatePixTransactionIfNoFinancialTransaction() -> PixTransaction não possui vinculo com a FinancialTransactionPixTransaction. [pixTransaction.id: ${pixTransaction.id}]")
            pixTransactionService.setAsError(pixTransaction)
        }
        return pixTransaction
    }

    private Boolean blockPixTransactionIfInsufficientInstantPaymentAccountBalance(PixTransaction pixTransaction) {
        if (pixInstantPaymentAccountService.hasEnoughBalanceForCheckout(pixTransaction)) return false
        pixTransactionService.awaitInstantPaymentAccountBalance(pixTransaction)
        return true
    }
}
