package com.asaas.service.asyncaction

import com.asaas.asaascard.AsaasCardStatus
import com.asaas.asaascard.AsaasCardType
import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.asyncaction.builder.AsyncActionDataDeserializer
import com.asaas.asyncaction.worker.financialtransactionaftersave.FinancialTransactionAfterSaveAsyncActionWorkerConfigVO
import com.asaas.asyncaction.worker.financialtransactionaftersave.FinancialTransactionAfterSaveAsyncActionWorkerItemVO
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asyncAction.FinancialTransactionAfterSaveAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.recurrentchargedfeeconfig.enums.RecurrentChargedFeeConfigStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class FinancialTransactionAfterSaveAsyncActionService {

    def asaasBacenJudManagerService
    def asaasErpAccountingStatementService
    def asyncActionService
    def baseAsyncActionService
    def confirmedFraudService
    def financialStatementItemForDomainObjectService
    def invoiceItemService
    def recurrentChargedFeeConfigService

    def grailsApplication

    public FinancialTransactionAfterSaveAsyncAction save(FinancialTransaction financialTransaction) {
        FinancialTransactionAfterSaveAsyncAction asyncAction = new FinancialTransactionAfterSaveAsyncAction()
        asyncAction.providerId = financialTransaction.provider.id
        return baseAsyncActionService.save(asyncAction, [financialTransactionId: financialTransaction.id])
    }

    public List<FinancialTransactionAfterSaveAsyncActionWorkerItemVO> listPendingItemsForWorker(FinancialTransactionAfterSaveAsyncActionWorkerConfigVO workerConfigVO, Integer pendingItemsLimit, List<Map> actionDataOnProcessingList) {
        List<Long> itemsOnProcessingIdList = actionDataOnProcessingList ? actionDataOnProcessingList.collect( { it.asyncActionId } ) : []

        final Integer delayInSeconds = 1
        final Date dateCreatedLimit = CustomDateUtils.sumSeconds(new Date(), delayInSeconds * -1)

        List<Map> pendingAsyncActionDataList = listPendingItems(pendingItemsLimit, dateCreatedLimit, itemsOnProcessingIdList)
        if (!pendingAsyncActionDataList) return []

        List<FinancialTransactionAfterSaveAsyncActionWorkerItemVO> itemList = []
        pendingAsyncActionDataList.collate(workerConfigVO.maxItemsPerThread).each { itemList.add(new FinancialTransactionAfterSaveAsyncActionWorkerItemVO(it)) }

        return itemList
    }

    public void processPendingItems(List<Map> asyncActionDataMapList) {
        final Integer flushEvery = 25
        final Integer batchSize = 25

        List<Long> errorItemIdList = []
        List<Long> processedItemIdList = []
        List<Long> transactionBatchIdList = []

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionDataMapList, batchSize, flushEvery, { Map asyncActionDataMap ->
            try {
                FinancialTransaction financialTransaction = FinancialTransaction.read(asyncActionDataMap.financialTransactionId)

                if (!financialTransaction) {
                    final Integer currentAttemptCount = incrementProcessingAttemptsCounter(asyncActionDataMap)

                    if (!shouldReprocessAsyncAction(currentAttemptCount)) {
                        AsaasLogger.warn("FinancialTransactionAfterSaveAsyncActionService.processPendingItems >>> Transação financeira não encontrada [${asyncActionDataMap.financialTransactionId}]")

                        errorItemIdList.add(asyncActionDataMap.asyncActionId)
                    }

                    return
                }

                processPendingItem(financialTransaction)

                transactionBatchIdList.add(asyncActionDataMap.asyncActionId)
            } catch (Exception exception) {
                transactionBatchIdList.clear()
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("FinancialTransactionAfterSaveAsyncActionService.processPendingItems >>> Ocorreu um lock durante o processamento [asyncActionId: ${asyncActionDataMap.asyncActionId}]", exception)
                } else {
                    AsaasLogger.error("FinancialTransactionAfterSaveAsyncActionService.processPendingItems >>> Ocorreu um erro durante o processamento [asyncActionId: ${asyncActionDataMap.asyncActionId}]", exception)

                    errorItemIdList.add(asyncActionDataMap.asyncActionId)
                }

                throw exception
            }
        }, [
            logErrorMessage: "FinancialTransactionAfterSaveAsyncActionService.processPendingItems >>> Erro ao processar asyncActions em lote",
            appendBatchToLogErrorMessage: true,
            logLockAsWarning: true,
            onEachTransactionEnd: {
                processedItemIdList.addAll(transactionBatchIdList)
                transactionBatchIdList.clear()
            }
        ])

        processErrorList(errorItemIdList)
        deleteProcessedList(processedItemIdList)
    }

    private void processPendingItem(FinancialTransaction financialTransaction) {
        asaasErpAccountingStatementService.onFinancialTransactionCreate(financialTransaction)

        if (financialTransaction.value > 0) {
            asaasBacenJudManagerService.saveReprocessLockForCustomerIfNecessary(financialTransaction.id, financialTransaction.provider)
            saveUnblockCreditCardIfNecessary(financialTransaction)
        }

        manageInvoiceItemIfNecessary(financialTransaction)
        generateFinancialStatementItem(financialTransaction)
        cancelAccountInactivityRecurrentChargedFeeIfNecessary(financialTransaction)
        confirmedFraudService.executeBalanceZeroingWhenBalanceChange(financialTransaction.provider)
    }

    private void processErrorList(List<Long> asyncActionErrorList) {
        final Integer flushEvery = 10
        final Integer batchSize = 10
        final Integer idListOperationCollateSize = 250

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionErrorList.collate(idListOperationCollateSize), batchSize, flushEvery, { List<Long> asyncActionErrorPartialList ->
            baseAsyncActionService.updateStatus(FinancialTransactionAfterSaveAsyncAction, asyncActionErrorPartialList, AsyncActionStatus.ERROR)
        }, [logErrorMessage: "FinancialTransactionAfterSaveAsyncActionService.processErrorList >> Erro ao processar setar asyncActions com ERRO em lote",
            appendBatchToLogErrorMessage: true])
    }

    private void deleteProcessedList(List<Long> asyncActionIdProcessedList) {
        final Integer flushEvery = 10
        final Integer batchSize = 10
        final Integer idListOperationCollateSize = 250

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(asyncActionIdProcessedList.collate(idListOperationCollateSize), batchSize, flushEvery, { List<Long> asyncActionIdProcessedPartialList ->
            baseAsyncActionService.deleteList(FinancialTransactionAfterSaveAsyncAction, asyncActionIdProcessedPartialList)
        }, [logErrorMessage: "FinancialTransactionAfterSaveAsyncActionService.deleteProcessedList >> Erro ao remover asyncActions processadas em lote",
            appendBatchToLogErrorMessage: true])
    }

    private List<Map> listPendingItems(Integer limit, Date dateCreatedLimit, List<Long> notInIdList) {
        FinancialTransactionAfterSaveAsyncAction.withSession { session ->
            final String queuesSchemaName = grailsApplication.config.asaas.database.schema.queues.name

            StringBuilder sql = new StringBuilder()
            sql.append(" SELECT id, action_data as actionData ")
            sql.append("   FROM ${queuesSchemaName}.financial_transaction_after_save_async_action ftasaa FORCE INDEX(primary) ")
            sql.append("  WHERE ftasaa.status = :status ")
            sql.append("    AND ftasaa.date_created < :dateCreatedLimit ")
            if (notInIdList) sql.append("    AND ftasaa.id NOT IN (:notInIdList) ")
            sql.append("  LIMIT :limit ")

            SQLQuery query = session.createSQLQuery(sql.toString())
            query.setString("status", AsyncActionStatus.PENDING.toString())
            query.setString("dateCreatedLimit", CustomDateUtils.fromDate(dateCreatedLimit, CustomDateUtils.DATABASE_DATETIME_FORMAT))
            if (notInIdList) query.setParameterList("notInIdList", notInIdList)
            query.setLong("limit", limit)

            List<List> asyncActionInfoList = query.list()

            return asyncActionInfoList.collect( { AsyncActionDataDeserializer.buildDataMap(it[1], Utils.toLong(it[0])) } )
        }
    }

    private void manageInvoiceItemIfNecessary(FinancialTransaction financialTransaction) {
        if (financialTransaction.isAsaasIncomeType()) {
            invoiceItemService.save(financialTransaction)
        } else if (FinancialTransactionType.getInvoiceDeletableTypes().contains(financialTransaction.transactionType)) {
            deleteInvoiceItem(financialTransaction)
        }
    }

    private void deleteInvoiceItem(FinancialTransaction financialTransaction) {
        FinancialTransaction reversedTransaction

        if (financialTransaction.reversedTransaction) {
            reversedTransaction = financialTransaction.reversedTransaction
        } else if (financialTransaction.transactionType.isBillPaymentFeeCancelled()) {
            reversedTransaction = FinancialTransaction.query([bill: financialTransaction.bill, transactionType: FinancialTransactionType.BILL_PAYMENT_FEE]).get()
        } else if (financialTransaction.transactionType.isRefundRequestFeeReversal()) {
            reversedTransaction = FinancialTransaction.query([refundRequest: financialTransaction.refundRequest, transactionType: FinancialTransactionType.REFUND_REQUEST_FEE]).get()
        } else if (financialTransaction.transactionType.isPaymentFeeReversal()) {
            reversedTransaction = FinancialTransaction.query([payment: financialTransaction.payment, transactionType: FinancialTransactionType.PAYMENT_FEE]).get()
        }

        if (reversedTransaction) invoiceItemService.delete(reversedTransaction)
    }

    private void generateFinancialStatementItem(FinancialTransaction financialTransaction) {
        financialStatementItemForDomainObjectService.saveForFinancialTransaction(financialTransaction)
    }

    private void saveUnblockCreditCardIfNecessary(FinancialTransaction financialTransaction) {
        Customer customer = financialTransaction.provider
        Long asaasCardId = AsaasCard.query([column: "id", customerId: customer.id, type: AsaasCardType.CREDIT, status: AsaasCardStatus.BLOCKED]).get()
        if (!asaasCardId) return

        BigDecimal balance = financialTransaction.getCustomerBalance(customer.id)
        if (balance >= 0) asyncActionService.saveUnblockAsaasCreditCardIfNecessary([asaasCardId: asaasCardId])
    }

    private Boolean shouldReprocessAsyncAction(Integer currentAttemptCount) {
        final Integer maxAttempts = 3
        Boolean shouldReprocessAsyncAction = currentAttemptCount < maxAttempts

        return shouldReprocessAsyncAction
    }

    private Integer incrementProcessingAttemptsCounter(Map asyncActionDataMap) {
        FinancialTransactionAfterSaveAsyncAction asyncAction = FinancialTransactionAfterSaveAsyncAction.get(asyncActionDataMap.asyncActionId)
        Map actionData = asyncAction.getDataAsMap()
        actionData.attempt = actionData.attempt ? actionData.attempt + 1 : 1
        asyncAction.actionData = AsyncActionDataBuilder.parseToJsonString(actionData)
        asyncAction.save(failOnError: true)

        return actionData.attempt
    }

    private void cancelAccountInactivityRecurrentChargedFeeIfNecessary(FinancialTransaction financialTransaction) {
        if (financialTransaction.transactionType.isAccountInactivityFee()) return

        Long recurrentChargedFeeConfigId = RecurrentChargedFeeConfig.query([column: "id", customerId: financialTransaction.providerId, type: ChargedFeeType.ACCOUNT_INACTIVITY, status: RecurrentChargedFeeConfigStatus.ACTIVE]).get()
        if (recurrentChargedFeeConfigId) {
            recurrentChargedFeeConfigService.setAsCancelled(recurrentChargedFeeConfigId)
        }
    }
}
