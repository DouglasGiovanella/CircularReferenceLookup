package com.asaas.service.dimp

import com.asaas.dimp.DimpBatchFileStatus
import com.asaas.dimp.DimpFileType
import com.asaas.dimp.DimpBillingType
import com.asaas.dimp.DimpPixTransactionType
import com.asaas.dimp.DimpStatus
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.domain.integration.dimp.DimpConfirmedTransaction
import com.asaas.domain.integration.dimp.DimpCustomer
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DimpConfirmedTransactionService {

    def dimpCustomerService
    def dimpFileService

    public Boolean createDimpConfirmedTransactions(Date startDate, Date endDate, List<PaymentStatus> paymentStatusList, Integer paymentListLimit, Boolean isRetroactive, List<Long> providerIdList) {
        Boolean hasAnyDimpConfirmedTransactionBeenCreated = false
        List<Long> paymentIdList = Payment.query(buildQueryParams(startDate, endDate, paymentStatusList, providerIdList)).list(max: paymentListLimit)

        if (!paymentIdList) return hasAnyDimpConfirmedTransactionBeenCreated

        Long dimpBatchFileId = dimpFileService.findOrCreateDimpBatchFile(DimpFileType.CONFIRMED_TRANSACTIONS, DimpBatchFileStatus.PENDING)

        final Integer numberOfThreads = 4
        final Integer batchSize = 50
        final Integer flushEvery = 50
        Utils.processWithThreads(paymentIdList, numberOfThreads, { List<Long> paymentIdListFromThread ->
            Utils.forEachWithFlushSessionAndNewTransactionInBatch(paymentIdListFromThread, batchSize, flushEvery, { Long paymentId ->
                Payment payment = Payment.read(paymentId)
                DimpConfirmedTransaction dimpConfirmedTransaction = saveIfNecessary(payment, dimpBatchFileId, isRetroactive)
                if (dimpConfirmedTransaction) hasAnyDimpConfirmedTransactionBeenCreated = true
            }, [logErrorMessage: "DimpConfirmedTransactionService.createDimpConfirmedTransactions >> erro ao salvar lote de DimpConfirmedTransaction",
                appendBatchToLogErrorMessage: true])
        })

        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)
            dimpFileService.setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_FILE_CREATION)
        }, [logErrorMessage: "DimpConfirmedTransactionService.createDimpConfirmedTransactions >> Erro ao alterar status do DimpBatchFile: ${dimpBatchFileId}"])

        return hasAnyDimpConfirmedTransactionBeenCreated
    }

    public Boolean shouldBeIgnored(DimpConfirmedTransaction dimpConfirmedTransaction) {
        if (!dimpConfirmedTransaction.customerAddressState) return true
        if (!dimpCustomerService.hasValidDimpCustomer(dimpConfirmedTransaction.dimpCustomer.cpfCnpj)) return true
        if (!dimpConfirmedTransaction.customerAccountCpfCnpj && dimpConfirmedTransaction.dimpBillingType.isLegalNatureStatementNecessary()) return true
        return false
    }

    public DimpConfirmedTransaction saveIfNecessary(Payment payment, Long dimpBatchFileId, Boolean isRetroactive) {
        Boolean dimpConfirmedTransactionAlreadyExists = DimpConfirmedTransaction.query([exists:true, paymentId: payment.id]).get().asBoolean()
        if (dimpConfirmedTransactionAlreadyExists) return null

        DimpCustomer dimpCustomer = DimpCustomer.query(cpfCnpj: payment.provider.cpfCnpj).get()
        if (!dimpCustomer) {
            AsaasLogger.warn("DimpConfirmedTransactionService.saveIfNecessary >> DimpCustomer não encontrado para o provider da cobrança ${payment.id}")
            return null
        }

        DimpConfirmedTransaction dimpConfirmedTransaction = new DimpConfirmedTransaction()
        dimpConfirmedTransaction.paymentId = payment.id
        dimpConfirmedTransaction.dimpCustomer = dimpCustomer
        dimpConfirmedTransaction.value = payment.value
        dimpConfirmedTransaction.installmentCount = parseInstallment(payment)

        if (isRetroactive) {
            DimpConfirmedTransaction retroactiveTransaction = DimpConfirmedTransaction.query([dimpCustomer: dimpCustomer, "confirmedDate[le]": payment.confirmedDate]).get()
            dimpConfirmedTransaction.customerAddressState = retroactiveTransaction?.customerAddressState ?: dimpCustomer.state
        } else {
            dimpConfirmedTransaction.customerAddressState = dimpCustomer.state
        }

        dimpConfirmedTransaction.confirmedDate = payment.confirmedDate

        Map creditCardTransactionInfoMap = CreditCardTransactionInfo.query([columnList: ["acquirer", "acquirerNetValue"], paymentId: payment.id]).get()
        dimpConfirmedTransaction.creditCardAcquirer = creditCardTransactionInfoMap?.acquirer
        if (creditCardTransactionInfoMap?.acquirerNetValue) {
            dimpConfirmedTransaction.acquirerNetValue = payment.value - creditCardTransactionInfoMap.acquirerNetValue
        } else {
            dimpConfirmedTransaction.acquirerNetValue = 0
        }

        dimpConfirmedTransaction.creditCardBrand = payment.billingInfo?.creditCardInfo?.brand
        dimpConfirmedTransaction.hasBeenSplit = payment.hasBeenSplit().asBoolean()
        dimpConfirmedTransaction.dimpBillingType = parseDimpBillingType(payment)
        dimpConfirmedTransaction.creditCardAcquirerEc = payment.id
        dimpConfirmedTransaction.customerAccountCpfCnpj = payment.customerAccount.cpfCnpj
        if (payment.billingType.isPix()) dimpConfirmedTransaction.dimpPixTransactionType = parseDimpPixTransactionType(payment)

        dimpConfirmedTransaction.status = shouldBeIgnored(dimpConfirmedTransaction) ? DimpStatus.IGNORED : DimpStatus.PENDING

        if (!dimpConfirmedTransaction.status.isIgnored()) dimpConfirmedTransaction.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)

        dimpConfirmedTransaction.save(failOnError: true)

        return dimpConfirmedTransaction
    }

    public Boolean reprocessPendingDimpBatchFile(Date startDate, Date endDate, Long dimpBatchFileId) {
        Map queryParams = [
            "column": "id",
            "confirmedDate[ge]": startDate,
            "confirmedDate[le]": endDate,
            "dimpBatchFile[isNull]": true,
            "status": DimpStatus.PENDING,
            "disableSort": true
        ]

        final Integer max = 5000
        List<Long> dimpConfirmedTransactionList = DimpConfirmedTransaction.query(queryParams).list(max: max)

        if (dimpConfirmedTransactionList.isEmpty()) return true

        if (!dimpBatchFileId) {
            dimpBatchFileId = dimpFileService.findOrCreateDimpBatchFile(DimpFileType.CONFIRMED_TRANSACTIONS, DimpBatchFileStatus.PENDING)
        }

        final Integer numberOfThreads = 4
        Utils.processWithThreads(dimpConfirmedTransactionList, numberOfThreads, { List<Long> dimpConfirmedTransactionListFromThread ->
            Utils.forEachWithFlushSession(dimpConfirmedTransactionListFromThread, 100, { Long dimpConfirmedTransactionId ->
                Utils.withNewTransactionAndRollbackOnError({
                    DimpConfirmedTransaction dimpConfirmedTransaction = DimpConfirmedTransaction.get(dimpConfirmedTransactionId)
                    dimpConfirmedTransaction.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)
                    dimpConfirmedTransaction.status = DimpStatus.DONE
                    dimpConfirmedTransaction.save(failOnError: true)
                }, [logErrorMessage: "DimpConfirmedTransactionService.reprocessPendingDimpBatchFile => Erro ao salvar a DimpConfirmedTransaction ${dimpConfirmedTransactionId}"])
            })
        })

        return false
    }

    private Map buildQueryParams(Date startDate, Date endDate, List<PaymentStatus> paymentStatusList, List<Long> providerIdList) {
        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.disableSort = true
        queryParams.statusList = paymentStatusList

        if (endDate) {
            queryParams."confirmedDate[ge]" = startDate
            queryParams."confirmedDate[le]" = endDate
        } else {
            queryParams."confirmedDate" = startDate
        }

        if (paymentStatusList.contains(PaymentStatus.REFUNDED)) {
            queryParams.refundedDateIsNullOrLessThan = endDate
        }

        if (providerIdList) queryParams."providerId[in]" = providerIdList

        return queryParams
    }

    private Integer parseInstallment(Payment payment) {
        if (payment.billingType.isDebitCard()) return 0
        if (payment.installment) return payment.installment.installmentCount
        return 1
    }

    private DimpBillingType parseDimpBillingType(Payment payment) {
        if (payment.isCreditCardInstallment()) return DimpBillingType.CREDIT_WITH_INSTALLMENTS
        if (payment.billingType.isCreditCard()) return DimpBillingType.CREDIT_WITHOUT_INSTALLMENTS
        if (payment.billingType.isDeposit()) return DimpBillingType.DEPOSIT
        if (payment.billingType.isTransfer()) return DimpBillingType.TRANSFER
        if (payment.billingType.isDebitCard()) return DimpBillingType.DEBIT
        if (payment.billingType.isBoleto()) return DimpBillingType.BOLETO
        if (payment.billingType.isPix()) return DimpBillingType.PIX

        return DimpBillingType.OTHER
    }

    private DimpPixTransactionType parseDimpPixTransactionType(Payment payment) {
        PixTransaction transaction = PixTransaction.query([payment: payment]).get()

        if (transaction?.originType?.isDynamicQrCode()) return DimpPixTransactionType.DYNAMIC
        return DimpPixTransactionType.STATIC
    }
}
