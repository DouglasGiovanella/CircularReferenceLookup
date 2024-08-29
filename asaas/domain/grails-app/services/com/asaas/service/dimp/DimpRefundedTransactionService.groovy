package com.asaas.service.dimp

import com.asaas.chargeback.ChargebackStatus
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.dimp.DimpBatchFileStatus
import com.asaas.dimp.DimpFileType
import com.asaas.dimp.DimpStatus
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.domain.integration.dimp.DimpConfirmedTransaction
import com.asaas.domain.integration.dimp.DimpRefundedTransaction
import com.asaas.domain.payment.Payment
import com.asaas.payment.PaymentStatus
import com.asaas.state.State
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class DimpRefundedTransactionService {

    def dimpCustomerService
    def dimpFileService

    public Boolean createDimpRefundedTransactions(Date startDate, Date endDate, State state, Integer limit) {
        Boolean hasAnyDimpRefundedTransactionBeenCreated = false

        Map queryParams = [:]
        queryParams.column = "id"
        queryParams.paymentStatus = PaymentStatus.REFUNDED
        queryParams."paymentRefundedDate[ge]" = startDate
        queryParams."paymentRefundedDate[le]" = endDate
        queryParams."dimpRefundedTransaction[notExists]" = true
        if (state) queryParams.customerAddressState = state

        List<Long> idList = DimpConfirmedTransaction.query(queryParams).list(max: limit)

        if (!idList) return hasAnyDimpRefundedTransactionBeenCreated

        Long dimpBatchFileId = dimpFileService.findOrCreateDimpBatchFile(DimpFileType.REFUNDED_TRANSACTIONS, DimpBatchFileStatus.PENDING)

        final Integer numberOfThreads = 4
        Utils.processWithThreads(idList, numberOfThreads, { List<Long> idListFromThread ->
            Utils.forEachWithFlushSession(idListFromThread, 50, { Long dimpConfirmedTransactionId ->
                Utils.withNewTransactionAndRollbackOnError({
                    DimpConfirmedTransaction dimpConfirmedTransaction = DimpConfirmedTransaction.get(dimpConfirmedTransactionId)
                    save(dimpConfirmedTransaction, dimpBatchFileId)

                    hasAnyDimpRefundedTransactionBeenCreated = true
                }, [logErrorMessage: "DimpRefundedTransactionService >> erro ao salvar DimpRefundedTransaction ${dimpConfirmedTransactionId}"])
            })
        })

        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)
            dimpFileService.setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_FILE_CREATION)
        }, [logErrorMessage: "DimpRefundedTransactionService.createDimpRefundedTransactions >> Erro ao alterar status do DimpBatchFile: ${dimpBatchFileId}"])

        return hasAnyDimpRefundedTransactionBeenCreated
    }

    public Boolean shouldBeIgnored(DimpRefundedTransaction dimpRefundedTransaction) {
        if (!dimpRefundedTransaction.customerAddressState) return true
        if (!dimpRefundedTransaction.customerCpfCnpj) return true
        if (!dimpCustomerService.hasValidDimpCustomer(dimpRefundedTransaction.customerCpfCnpj)) return true

        return false
    }

    private DimpRefundedTransaction save(DimpConfirmedTransaction dimpConfirmedTransaction, Long dimpBatchFileId) {
        DimpRefundedTransaction dimpRefundedTransaction = new DimpRefundedTransaction()
        dimpRefundedTransaction.dimpConfirmedTransaction = dimpConfirmedTransaction
        dimpRefundedTransaction.paymentId = dimpConfirmedTransaction.paymentId
        Payment refundedPayment = Payment.read(dimpConfirmedTransaction.paymentId)
        dimpRefundedTransaction.value = refundedPayment.value
        dimpRefundedTransaction.refundedDate = refundedPayment.refundedDate
        dimpRefundedTransaction.transactionConfirmedDate = refundedPayment.confirmedDate
        dimpRefundedTransaction.customerCpfCnpj = dimpConfirmedTransaction.dimpCustomer.cpfCnpj
        dimpRefundedTransaction.customerAddressState = dimpConfirmedTransaction.customerAddressState

        CreditCardAcquirer creditCardAcquirer = CreditCardTransactionInfo.query([column: "acquirer", paymentId: dimpConfirmedTransaction.paymentId]).get()
        dimpRefundedTransaction.creditCardAcquirer = creditCardAcquirer
        dimpRefundedTransaction.creditCardBrand = refundedPayment.billingInfo?.creditCardInfo?.brand

        if (refundedPayment.installment) {
            dimpRefundedTransaction.isChargeback = Chargeback.query([exists: true, status: ChargebackStatus.DONE, installment: refundedPayment.installment]).get().asBoolean()
        } else {
            dimpRefundedTransaction.isChargeback = Chargeback.query([exists: true, status: ChargebackStatus.DONE, payment: refundedPayment]).get().asBoolean()
        }

        dimpRefundedTransaction.status = shouldBeIgnored(dimpRefundedTransaction) ? DimpStatus.IGNORED : DimpStatus.PENDING

        if (!dimpRefundedTransaction.status.isIgnored()) dimpRefundedTransaction.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)

        dimpRefundedTransaction.save(failOnError: true)

        return dimpRefundedTransaction
    }

}
