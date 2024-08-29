package com.asaas.service.dimp

import com.asaas.customer.CustomerParameterName
import com.asaas.dimp.DimpBatchFileStatus
import com.asaas.dimp.DimpFileType
import com.asaas.dimp.DimpStatus
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.integration.dimp.DimpConfirmedPixTransaction
import com.asaas.domain.integration.dimp.DimpRefundedPixTransaction
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionRefund
import com.asaas.pix.PixTransactionStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DimpRefundedPixTransactionService {

    def dimpCustomerService
    def dimpFileService

    public Boolean createDimpRefundedTransactionsForRefundedDate(Date pixRefundedDate) {
        List<Long> customerIdList = CustomerParameter.query([column: "customer.id", "name": CustomerParameterName.ENABLE_PIX_CREDIT_TRANSACTION_WITHOUT_PAYMENT, "includeDeleted": true]).list()
        Map queryParams = buildPixCreditRefundQueryParams(pixRefundedDate, customerIdList)
        List<Long> pixTransactionCreditRefundedIdList = PixTransaction.creditRefund(queryParams).list()
        if (!pixTransactionCreditRefundedIdList) return false

        Long dimpBatchFileId = dimpFileService.findOrCreateDimpBatchFile(DimpFileType.REFUNDED_PIX_TRANSACTIONS, DimpBatchFileStatus.PENDING)

        Boolean hasAnyDimpRefundedPixTransactionBeenCreated = createDimpRefundedTransactions(pixTransactionCreditRefundedIdList, dimpBatchFileId)

        if (hasAnyDimpRefundedPixTransactionBeenCreated) setRefundedPixTransactionBatchAsAwaitingFileCreation(dimpBatchFileId)

        return hasAnyDimpRefundedPixTransactionBeenCreated
    }

    private Map buildPixCreditRefundQueryParams(Date pixRefundedDate, List<Long> customerIdList) {
        Map queryParams = [:]
        queryParams.column = "id"
        queryParams."customerId[in]" = customerIdList
        queryParams."payment[isNull]" = true
        queryParams.status = PixTransactionStatus.DONE
        queryParams."effectiveDate[ge]" = pixRefundedDate
        queryParams."effectiveDate[le]" = CustomDateUtils.setTimeToEndOfDay(pixRefundedDate)
        queryParams."dimpRefundedPixTransaction[notExists]" = true
        queryParams.disableSort = true

        return queryParams
    }

    private Boolean createDimpRefundedTransactions(List<Long> idList, long dimpBatchFileId) {
        Boolean hasAnyDimpRefundedPixTransactionBeenCreated = false
        final Integer numberOfItemsPerThread = 2500
        final Integer batchSize = 100
        final Integer flushEvery = 100
        ThreadUtils.processWithThreadsOnDemand(idList, numberOfItemsPerThread, { List<Long> idSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(idSubList, batchSize, flushEvery, { Long pixTransactionId ->
                PixTransaction pixTransaction = PixTransaction.read(pixTransactionId)

                DimpRefundedPixTransaction dimpRefundedPixTransaction = saveIfNecessary(pixTransaction, dimpBatchFileId)
                if (dimpRefundedPixTransaction) hasAnyDimpRefundedPixTransactionBeenCreated = true
            }, [logErrorMessage             : "DimpRefundedPixTransactionService.createDimpRefundedTransactions >> erro ao salvar lote de DimpRefundedPixTransaction",
                appendBatchToLogErrorMessage: true])
        })

        return hasAnyDimpRefundedPixTransactionBeenCreated
    }

    private DimpRefundedPixTransaction saveIfNecessary(PixTransaction pixTransaction, Long dimpBatchFileId) {
        Long refundedPixTransactionId = PixTransactionRefund.query([column: "refundedTransaction.id", transaction: pixTransaction]).get()
        DimpConfirmedPixTransaction dimpConfirmedPixTransaction = DimpConfirmedPixTransaction.query([pixTransactionId: refundedPixTransactionId]).get()
        if (!dimpConfirmedPixTransaction) return null

        DimpRefundedPixTransaction dimpRefundedPixTransaction = new DimpRefundedPixTransaction()
        dimpRefundedPixTransaction.customerAddressState = dimpConfirmedPixTransaction.customerAddressState
        dimpRefundedPixTransaction.customerCpfCnpj = dimpConfirmedPixTransaction.dimpCustomer.cpfCnpj
        dimpRefundedPixTransaction.dimpConfirmedPixTransaction = dimpConfirmedPixTransaction
        dimpRefundedPixTransaction.pixTransaction = pixTransaction
        dimpRefundedPixTransaction.refundedDate = pixTransaction.effectiveDate
        dimpRefundedPixTransaction.transactionConfirmedDate = dimpConfirmedPixTransaction.confirmedDate
        dimpRefundedPixTransaction.value = BigDecimalUtils.abs(pixTransaction.value)

        dimpRefundedPixTransaction.status = shouldBeIgnored(dimpRefundedPixTransaction) ? DimpStatus.IGNORED : DimpStatus.PENDING

        if (!dimpRefundedPixTransaction.status.isIgnored()) dimpRefundedPixTransaction.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)

        dimpRefundedPixTransaction.save(failOnError: true)

        return dimpRefundedPixTransaction
    }

    private void setRefundedPixTransactionBatchAsAwaitingFileCreation(long dimpBatchFileId) {
        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)
            dimpFileService.setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_FILE_CREATION)
        }, [logErrorMessage: "DimpRefundedPixTransactionService.setRefundedPixTransactionBatchAsAwaitingFileCreation >> Erro ao alterar status do DimpBatchFile: ${dimpBatchFileId}"])
    }

    private Boolean shouldBeIgnored(DimpRefundedPixTransaction dimpRefundedPixTransaction) {
        if (!dimpRefundedPixTransaction.customerAddressState) return true
        if (!dimpRefundedPixTransaction.customerCpfCnpj) return true
        if (!dimpCustomerService.hasValidDimpCustomer(dimpRefundedPixTransaction.customerCpfCnpj)) return true

        return false
    }
}
