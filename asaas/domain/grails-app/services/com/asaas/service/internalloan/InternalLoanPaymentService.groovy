package com.asaas.service.internalloan

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.internalloan.InternalLoanItem
import com.asaas.domain.internalloan.InternalLoanPayment
import com.asaas.domain.internalloan.InternalLoanPaymentItem
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.internalloan.InternalLoanPaymentStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InternalLoanPaymentService {

    def internalTransferService

    public InternalLoanPayment save(InternalLoanItem internalLoanItem) {
        InternalLoanPayment internalLoanPayment = new InternalLoanPayment()
        internalLoanPayment.debtor = internalLoanItem.internalLoan.debtor
        internalLoanPayment.creditor = internalLoanItem.internalLoan.guarantor
        internalLoanPayment.value = internalLoanItem.value
        internalLoanPayment.remainingValue = internalLoanPayment.value
        internalLoanPayment.origin = internalLoanItem
        internalLoanPayment.save(failOnError: true)

        return internalLoanPayment
    }

    public void processPendingInternalLoanPayments() {
        List<Long> debtorIdList = InternalLoanPayment.pending([distinct: "debtor.id", "debtorHasPositiveBalance": true, disableSort: true]).list(max: 100)
        if (!debtorIdList) return

        List<Long> internalLoanPaymentIdList = InternalLoanPayment.pending([column: "id", "debtorId[in]": debtorIdList, order: "asc"]).list(max: 100)

        for (Long internalLoanPaymentId in internalLoanPaymentIdList) {
            processInternalLoanPayment(internalLoanPaymentId)
        }
    }

    private void processInternalLoanPayment(Long internalLoanPaymentId) {
        Utils.withNewTransactionAndRollbackOnError({
            InternalLoanPayment internalLoanPayment = InternalLoanPayment.get(internalLoanPaymentId)

            BigDecimal debtorBalance = FinancialTransaction.getCustomerBalance(internalLoanPayment.debtor)
            if (debtorBalance <= 0) return

            BigDecimal paidValue = BigDecimalUtils.min(internalLoanPayment.remainingValue, BigDecimalUtils.abs(debtorBalance))

            InternalTransfer internalTransfer = internalTransferService.save(internalLoanPayment, paidValue)

            saveItem(internalLoanPayment, internalTransfer)
        }, [logErrorMessage: "InternalLoanPaymentService >> Erro ao processar pagamento pendente [id: ${internalLoanPaymentId}]"])
    }

    private InternalLoanPaymentItem saveItem(InternalLoanPayment internalLoanPayment, InternalTransfer internalTransfer) {
        InternalLoanPaymentItem item = new InternalLoanPaymentItem()
        item.internalLoanPayment = internalLoanPayment
        item.internalTransfer = internalTransfer
        item.value = internalTransfer.value
        item.save(flush: true, failOnError: true)

        setAsPaidIfPossible(internalLoanPayment)

        return item
    }

    private InternalLoanPayment setAsPaidIfPossible(InternalLoanPayment internalLoanPayment) {
        BigDecimal totalValue = InternalLoanPaymentItem.sumValue([internalLoanPayment: internalLoanPayment]).get()

        if (internalLoanPayment.value == totalValue) {
            internalLoanPayment.remainingValue = 0
            internalLoanPayment.status = InternalLoanPaymentStatus.PAID
            internalLoanPayment.save(failOnError: true)
        } else {
            internalLoanPayment.remainingValue = internalLoanPayment.value - totalValue
            internalLoanPayment.save(failOnError: true)
        }

        return internalLoanPayment
    }
}
