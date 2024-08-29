package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCardAcquirerOperationEnum
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationBatchStatus
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationStatus
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcardacquireroperation.CreditCardAcquirerOperationBatch
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditCardAcquirerOperationBatchProcessService {

    def creditCardAcquirerOperationService
    def creditCardTransactionInfoService
    def customerInteractionService
    def messageService

    public void approvePendingBatches() {
        List<Long> creditCardAcquirerOperationBatchList = CreditCardAcquirerOperationBatch.query([column: "id", status: CreditCardAcquirerOperationBatchStatus.PENDING, order: "asc"]).list()
        Integer numberOfBatchesBeforeFlush = 3

        Utils.forEachWithFlushSession(creditCardAcquirerOperationBatchList, numberOfBatchesBeforeFlush, { Long creditCardAcquirerOperationBatchId ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch = CreditCardAcquirerOperationBatch.get(creditCardAcquirerOperationBatchId)

                final Integer numberOfThreadsToPrepareOperationList = 3
                List<Long> creditCardAcquirerOperationIdList = CreditCardAcquirerOperation.query([column: "id", creditCardAcquirerOperationBatchId: creditCardAcquirerOperationBatch.id, status: CreditCardAcquirerOperationStatus.PENDING]).list()

                Utils.processWithThreads(creditCardAcquirerOperationIdList, numberOfThreadsToPrepareOperationList, { List<Long> idList ->
                    prepareOperationList(idList)
                })

                creditCardAcquirerOperationBatch.status = CreditCardAcquirerOperationBatchStatus.APPROVED
                creditCardAcquirerOperationBatch.save(failOnError: true)
            }, [logErrorMessage: "CreditCardAcquirerOperationBatchProcessService.approvePendingBatches >>> Não foi possível aprovar o lote [creditCardAcquirerOperationBatchId: ${creditCardAcquirerOperationBatchId}]"])
        })
    }

    public void processApproved() {
        Long batchId = CreditCardAcquirerOperationBatch.query([column: "id", status: CreditCardAcquirerOperationBatchStatus.APPROVED, sort: "id", order: "asc"]).get()
        if (!batchId) return

        final Integer numberOfThreads = 6
        final Integer maxNumberOfCustomers = 3000

        List<Long> customerIdList = CreditCardAcquirerOperation.depositReadyToProcess([
            distinct: "customer.id",
            creditCardAcquirerOperationBatchId: batchId,
            disableSort: true
        ]).list(max: maxNumberOfCustomers)

        if (!customerIdList) {
            setBatchAsProcessed(batchId)
            return
        }

        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> idList ->
            processDepositList(batchId, idList)
        })
    }

    public void setBatchAsProcessed(Long batchId) {
        CreditCardAcquirerOperationBatch creditCardAcquirerOperationBatch = CreditCardAcquirerOperationBatch.get(batchId)
        creditCardAcquirerOperationBatch.status = CreditCardAcquirerOperationBatchStatus.PROCESSED
        creditCardAcquirerOperationBatch.processedDate = new Date()
        creditCardAcquirerOperationBatch.save(failOnError: true)

        Long itemsWithError = CreditCardAcquirerOperation.query([column: "id", creditCardAcquirerOperationBatchId: batchId, status: CreditCardAcquirerOperationStatus.ERROR]).count()
        messageService.sendCreditCardAcquirerOperationBatchProcessResult(batchId, itemsWithError)
    }

    private void processDepositList(Long batchId, List<Long> customerIdList) {
        final Integer numberOfOperations = 500
        List<Long> creditCardAcquirerOperationDepositIdList = CreditCardAcquirerOperation.depositReadyToProcess([column: "id", creditCardAcquirerOperationBatchId: batchId, "customerId[in]": customerIdList]).list(max: numberOfOperations)

        Utils.forEachWithFlushSession(creditCardAcquirerOperationDepositIdList, 50, { Long creditCardAcquirerOperationId ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditCardAcquirerOperation creditCardAcquirerOperation = CreditCardAcquirerOperation.get(creditCardAcquirerOperationId)

                Payment payment = creditCardAcquirerOperation.payment
                Boolean hasPaymentForAcquirer = CreditCardTransactionInfo.query([exists: true, paymentId: payment.id, acquirer: creditCardAcquirerOperation.creditCardAcquirerOperationBatch.acquirer]).get().asBoolean()

                if (hasPaymentForAcquirer) {
                    creditCardTransactionInfoService.updateAcquirerInfo(creditCardAcquirerOperation)
                } else {
                    AsaasLogger.warn("CreditCardAcquirerOperationBatchProcessService.processDepositList >>> Data de estimativa não foi atualizada devido a adquirente da cobrança ser diferente. [paymentId: ${payment.id}")
                }

                if (existsRefundTransactionInBatch(creditCardAcquirerOperation)) {
                    creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.IGNORED
                    creditCardAcquirerOperation.details = "Depósito ignorado pois há registro de cancelamento."
                } else {
                    creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.PROCESSED
                }

                creditCardAcquirerOperation.save(failOnError: true)
            }, [onError: { Exception exception ->
                AsaasLogger.error("Erro ao processar CreditCardAcquirerOperation [${creditCardAcquirerOperationId}].", exception)
                Utils.withNewTransactionAndRollbackOnError({
                    CreditCardAcquirerOperation creditCardAcquirerOperation = CreditCardAcquirerOperation.get(creditCardAcquirerOperationId)
                    creditCardAcquirerOperationService.setAsError(creditCardAcquirerOperation, exception.getMessage())
                }, [logErrorMessage: "Erro ao setar o status ERROR para CreditCardAcquirerOperation [${creditCardAcquirerOperationId}]"])
            }])
        })
    }

    private void prepareOperationList(List<Long> creditCardAcquirerOperationIdList) {
        Utils.forEachWithFlushSession(creditCardAcquirerOperationIdList, 50, { Long creditCardAcquirerOperationId ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditCardAcquirerOperation creditCardAcquirerOperation = CreditCardAcquirerOperation.get(creditCardAcquirerOperationId)

                if (creditCardAcquirerOperation.operation.isSecondChargeback()) {
                    creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.IGNORED
                    creditCardAcquirerOperation.save(failOnError: true)
                    return
                }

                Payment payment = findPayment(creditCardAcquirerOperation)

                if (!payment) {
                    processWithoutPayment(creditCardAcquirerOperation)
                    return
                }

                creditCardAcquirerOperation.payment = payment
                creditCardAcquirerOperation.customer = payment.provider
                creditCardAcquirerOperation.save(failOnError: true)

                if (creditCardAcquirerOperation.operation.isDeposit()) {
                    applyCreditCardGatewayFee(creditCardAcquirerOperation)
                    return
                }

                processNonDeposit(creditCardAcquirerOperation)
            }, [logErrorMessage: "Erro ao processar CreditCardAcquirerOperation [${creditCardAcquirerOperationId}]."])
        })
    }

    private CreditCardAcquirerOperation applyCreditCardGatewayFee(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        BigDecimal gatewayFee = 0
        CreditCardGateway gateway = CreditCardTransactionInfo.query([column: "gateway", paymentId: creditCardAcquirerOperation.payment.id]).get()

        if (gateway?.isCybersource()) {
            gatewayFee = 0.15
        } else if (gateway?.isMundipagg()) {
            gatewayFee = 0.30
        }

        if (!gatewayFee) return creditCardAcquirerOperation

        creditCardAcquirerOperation.netValue = BigDecimalUtils.max(creditCardAcquirerOperation.netValue - gatewayFee, 0)
        creditCardAcquirerOperation.save(failOnError: true)

        return creditCardAcquirerOperation
    }

    private CreditCardAcquirerOperation processWithoutPayment(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        switch (creditCardAcquirerOperation.operation) {
            case { it.isRefund() }:
            case { it.isInvoiceDeduction() }:
            case { it.isBalanceTransfer() }:
            case { it.isBulkAdvancement() }:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.IGNORED
                break
            case { it.isDeposit() && existsRefundTransactionInBatch(creditCardAcquirerOperation) }:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.IGNORED
                creditCardAcquirerOperation.details = "Depósito ignorado pois há registro de Cancelamento."
                break
            default:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.ERROR
                creditCardAcquirerOperation.details = "Cobrança com TID [${creditCardAcquirerOperation.transactionIdentifier}] não encontrada."
                break
        }

        creditCardAcquirerOperation.save(failOnError: true)

        return creditCardAcquirerOperation
    }

    private CreditCardAcquirerOperation processNonDeposit(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        switch (creditCardAcquirerOperation.operation) {
            case { it.isRefund() }:
                executeRefund(creditCardAcquirerOperation)
                break
            case { it.isPartialRefund() }:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.ERROR
                creditCardAcquirerOperation.details = "Cancelamento parcial não suportado, necessário tratar manualmente."
                break
            case { it.isChargeback() }:
                executeChargeback(creditCardAcquirerOperation)
                break
            case { it.isChargebackRefund() }:
            case { it.isRefundedReversed() }:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.PROCESSED
                creditCardAcquirerOperation.details = null
                break
            default:
                creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.ERROR
                creditCardAcquirerOperation.details = "Operação não suportada. Entre em contato com o time de Engenharia."
        }

        creditCardAcquirerOperation.save(failOnError: true)

        return creditCardAcquirerOperation
    }

    private void executeRefund(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        if (creditCardAcquirerOperation.payment.isRefunded()) {
            creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.PROCESSED
            creditCardAcquirerOperation.details = null
        } else {
            creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.ERROR
            creditCardAcquirerOperation.details = "A cobrança não está estornada no Asaas, faça o estorno."
        }
    }

    private void executeChargeback(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        creditCardAcquirerOperation.status = CreditCardAcquirerOperationStatus.PROCESSED
        creditCardAcquirerOperation.details = null

        customerInteractionService.saveChargebackReceived(creditCardAcquirerOperation.payment.provider, creditCardAcquirerOperation.payment.getInvoiceNumber(), creditCardAcquirerOperation.value)
    }

    private Payment findPayment(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        Payment payment = Payment.query([creditCardTid: creditCardAcquirerOperation.transactionIdentifier, installmentNumber: creditCardAcquirerOperation.installmentNumber]).get()

        if (payment) return payment

        if (creditCardAcquirerOperation.installmentNumber <= 1) payment = Payment.query([creditCardTid: creditCardAcquirerOperation.transactionIdentifier, installmentIsNull: true]).get()

        return payment
    }

    private Boolean existsRefundTransactionInBatch(CreditCardAcquirerOperation creditCardAcquirerOperation) {
        Boolean hasFullRefundInSameBatch = CreditCardAcquirerOperation.executeQuery("select count(id) from CreditCardAcquirerOperation where transactionIdentifier = :transactionIdentifier and creditCardAcquirerOperationBatch.id = :creditCardAcquirerOperationBatchId and operation = :operation and abs(value) >= :value and deleted = false", [transactionIdentifier: creditCardAcquirerOperation.transactionIdentifier, creditCardAcquirerOperationBatchId: creditCardAcquirerOperation.creditCardAcquirerOperationBatch.id, operation: [CreditCardAcquirerOperationEnum.REFUND], value: creditCardAcquirerOperation.value])[0].asBoolean()
        if (hasFullRefundInSameBatch) return true

        Boolean hasRefundWithoutValueInSameBatch = CreditCardAcquirerOperation.query([exists: true, transactionIdentifier: creditCardAcquirerOperation.transactionIdentifier, creditCardAcquirerOperationBatchId: creditCardAcquirerOperation.creditCardAcquirerOperationBatch.id, operation: CreditCardAcquirerOperationEnum.REFUND, value: new BigDecimal(0)]).get().asBoolean()
        if (hasRefundWithoutValueInSameBatch) return true

        return false
    }
}
