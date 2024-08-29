package com.asaas.service.dimp

import com.asaas.customer.CustomerParameterName
import com.asaas.dimp.DimpBatchFileStatus
import com.asaas.dimp.DimpFileType
import com.asaas.dimp.DimpPixTransactionType
import com.asaas.dimp.DimpStatus
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.integration.dimp.DimpConfirmedPixTransaction
import com.asaas.domain.integration.dimp.DimpCustomer
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DimpConfirmedPixTransactionService {

    def dimpCustomerService
    def dimpFileService

    public Boolean createDimpConfirmedPixTransactionsForEffectiveDate(Date pixEffectiveDate, Boolean isRetroactive, List<Long> customerIdList) {
        final Integer pixTransactionsLimit = 100000
        if (!customerIdList) customerIdList = CustomerParameter.query([column: "customer.id", "name": CustomerParameterName.ENABLE_PIX_CREDIT_TRANSACTION_WITHOUT_PAYMENT, "includeDeleted": true]).list()
        List<Long> pixTransactionIdList = PixTransaction.credit(buildPixQueryParams(pixEffectiveDate, customerIdList)).list(max: pixTransactionsLimit)

        if (!pixTransactionIdList) return false

        Long dimpBatchFileId = dimpFileService.createDimpBatchFile(DimpFileType.CONFIRMED_PIX_TRANSACTIONS, DimpBatchFileStatus.PENDING)

        Boolean hasAnyDimpConfirmedTransactionCreatedFromPix = createDimpConfirmedTransactionFromPixList(pixTransactionIdList, dimpBatchFileId, isRetroactive)
        if (hasAnyDimpConfirmedTransactionCreatedFromPix) setConfirmedPixTransactionBatchAsAwaitingFileCreation(dimpBatchFileId)

        return hasAnyDimpConfirmedTransactionCreatedFromPix
    }

    public void setConfirmedPixTransactionBatchAsAwaitingFileCreation(Long dimpBatchFileId) {
        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)
            dimpFileService.setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_FILE_CREATION)
        }, [logErrorMessage: "DimpConfirmedPixTransactionService.setConfirmedPixTransactionBatchAsAwaitingFileCreation >> Erro ao alterar status do DimpBatchFile ${dimpBatchFileId}"])
    }

    public DimpConfirmedPixTransaction saveIfNecessary(PixTransaction pixTransaction, Long dimpBatchFileId, Boolean isRetroactive) {
        DimpCustomer dimpCustomer = DimpCustomer.query(cpfCnpj: pixTransaction.customer.cpfCnpj).get()
        if (!dimpCustomer) {
            AsaasLogger.warn("DimpConfirmedPixTransactionService.saveIfNecessary >> DimpCustomer não encontrado para o customer da transação pix ${pixTransaction.id}")
            return null
        }

        DimpConfirmedPixTransaction dimpConfirmedPixTransaction = new DimpConfirmedPixTransaction()
        dimpConfirmedPixTransaction.pixTransaction = pixTransaction
        dimpConfirmedPixTransaction.confirmedDate = pixTransaction.effectiveDate
        dimpConfirmedPixTransaction.customerAccountCpfCnpj = pixTransaction.externalAccount.cpfCnpj
        dimpConfirmedPixTransaction.dimpPixTransactionType = parsePixTransactionType(pixTransaction)
        dimpConfirmedPixTransaction.dimpCustomer = dimpCustomer
        dimpConfirmedPixTransaction.value = pixTransaction.value

        if (isRetroactive) {
            String customerAddressStateRetroactiveTransaction = DimpConfirmedPixTransaction.query([column: "customerAddressState", dimpCustomerId: dimpCustomer.id, "confirmedDate[le]": pixTransaction.effectiveDate]).get()
            dimpConfirmedPixTransaction.customerAddressState = customerAddressStateRetroactiveTransaction ?: dimpCustomer.state
        } else {
            dimpConfirmedPixTransaction.customerAddressState = dimpCustomer.state
        }

        dimpConfirmedPixTransaction.status = shouldBeIgnored(dimpConfirmedPixTransaction) ? DimpStatus.IGNORED : DimpStatus.PENDING
        if (!dimpConfirmedPixTransaction.status.isIgnored()) dimpConfirmedPixTransaction.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)

        dimpConfirmedPixTransaction.save(failOnError: true)

        return dimpConfirmedPixTransaction
    }

    private Map buildPixQueryParams(Date pixEffectiveDate, List<Long> customerIdList) {
        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.disableSort = true
        queryParams.status = PixTransactionStatus.DONE
        queryParams."payment[isNull]" = true
        queryParams."effectiveDate[ge]" = pixEffectiveDate
        queryParams."effectiveDate[le]" = CustomDateUtils.setTimeToEndOfDay(pixEffectiveDate)
        queryParams."customerId[in]" = customerIdList
        queryParams."dimpConfirmedPixTransaction[notExists]" = true

        return queryParams
    }

    private Boolean createDimpConfirmedTransactionFromPixList(List<Long> pixTransactionIdList, Long dimpBatchFileId, Boolean isRetroactive) {
        Boolean hasAnyDimpConfirmedPixTransactionBeenCreated = false

        final Integer numberOfItemsPerThread = 25000
        final Integer batchSize = 100
        final Integer flushEvery = 100
        ThreadUtils.processWithThreadsOnDemand(pixTransactionIdList, numberOfItemsPerThread, { List<Long> pixTransactionIdSubList ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(pixTransactionIdSubList, batchSize, flushEvery, { Long pixTransactionId ->
                PixTransaction pixTransaction = PixTransaction.read(pixTransactionId)
                DimpConfirmedPixTransaction dimpConfirmedPixTransaction = saveIfNecessary(pixTransaction, dimpBatchFileId, isRetroactive)
                if (dimpConfirmedPixTransaction) hasAnyDimpConfirmedPixTransactionBeenCreated = true
            }, [logErrorMessage: "DimpConfirmedPixTransactionService.createDimpConfirmedTransactionFromPixList >> erro ao salvar lote de DimpConfirmedPixTransaction",
                appendBatchToLogErrorMessage: true])
        })

        return hasAnyDimpConfirmedPixTransactionBeenCreated
    }

    private DimpPixTransactionType parsePixTransactionType(PixTransaction pixTransaction) {
        if (pixTransaction?.originType?.isDynamicQrCode()) return DimpPixTransactionType.DYNAMIC

        return DimpPixTransactionType.STATIC
    }

    private Boolean shouldBeIgnored(DimpConfirmedPixTransaction dimpConfirmedPixTransaction) {
        if (!dimpConfirmedPixTransaction.customerAddressState) return true
        if (!dimpCustomerService.hasValidDimpCustomer(dimpConfirmedPixTransaction.dimpCustomer.cpfCnpj)) return true
        if (!dimpConfirmedPixTransaction.customerAccountCpfCnpj) return true

        return false
    }
}
