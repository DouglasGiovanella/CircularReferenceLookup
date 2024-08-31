package com.asaas.service.internalloan

import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.internalloan.InternalLoan
import com.asaas.domain.internalloan.InternalLoanConfig
import com.asaas.domain.internalloan.InternalLoanItem
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.internalloan.InternalLoanStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InternalLoanService {

    def internalLoanPaymentService
    def internalTransferService

    public InternalLoan saveIfNecessary(FinancialTransaction financialTransaction) {
        InternalLoanConfig internalLoanConfig = InternalLoanConfig.query([debtor: financialTransaction.provider]).get()
        if (!internalLoanConfig) return
        if (!internalLoanConfig.enabled) return

        BigDecimal balance = FinancialTransaction.getCustomerBalance(internalLoanConfig.debtor)
        if (balance >= 0) return

        BigDecimal previousBalance = balance - BigDecimalUtils.abs(financialTransaction.value)

        BigDecimal debtValue
        if (previousBalance <= 0) {
            debtValue = BigDecimalUtils.abs(financialTransaction.value)
        } else {
            debtValue = BigDecimalUtils.abs(balance)
        }

        InternalLoan internalLoan = new InternalLoan()
        internalLoan.guarantor = internalLoanConfig.guarantor
        internalLoan.debtor = internalLoanConfig.debtor
        internalLoan.value = debtValue
        internalLoan.remainingValue = internalLoan.value
        internalLoan.originTransaction = financialTransaction
        internalLoan.save(failOnError: true)

        return internalLoan
    }

    public InternalLoan cancelPendingLoan(FinancialTransaction financialTransaction) {
        InternalLoan internalLoan = InternalLoan.pending([debtor: financialTransaction.provider, originTransaction: financialTransaction]).get()
        if (!internalLoan) return

        return setAsCancelled(internalLoan)
    }

    public void cancelAllPendingLoansByDebtorAndGuarantor(Customer debtor, Customer guarantor) {
        List<Long> internalLoanIdList = InternalLoan.pending([column: "id", debtor: debtor, guarantor: guarantor]).list()

        Utils.forEachWithFlushSession(internalLoanIdList, 50, { Long internalLoanId ->
            Utils.withNewTransactionAndRollbackOnError({
                InternalLoan internalLoan = InternalLoan.get(internalLoanId)
                processInternalLoanWhenDebtorHasPositiveBalance(internalLoan)
            }, [logErrorMessage: "InternalLoanService >> Erro ao cancelar empréstimo pendente [id: ${internalLoanId}]"])
        })
    }

    public void processPendingInternalLoans() {
        List<Customer> guarantorList = InternalLoan.pending([distinct: "guarantor", "guarantorHasPositiveBalance": true, disableSort: true]).list(max: 10, readOnly: true)

        for (Customer guarantor in guarantorList) {
            List<Long> internalLoanIdList = InternalLoan.pending([column: "id", guarantor: guarantor, order: "asc"]).list(max: 100)

            for (Long internalLoanId in internalLoanIdList) {
                processInternalLoan(internalLoanId)
            }
        }
    }

    private void processInternalLoan(Long internalLoanId) {
        Utils.withNewTransactionAndRollbackOnError({
            InternalLoan internalLoan = InternalLoan.get(internalLoanId)

            BigDecimal debtorBalance = FinancialTransaction.getCustomerBalance(internalLoan.debtor)
            if (debtorBalance >= 0) {
                processInternalLoanWhenDebtorHasPositiveBalance(internalLoan)
                return
            }

            BigDecimal guarantorBalance = FinancialTransaction.getCustomerBalance(internalLoan.guarantor)
            if (guarantorBalance <= 0) return

            BigDecimal paidValue = getValueToBePaid(internalLoan, debtorBalance, guarantorBalance)

            InternalTransfer internalTransfer = internalTransferService.save(internalLoan, paidValue)

            InternalLoanItem item = saveItem(internalLoan, internalTransfer)
            internalLoanPaymentService.save(item)
        }, [logErrorMessage: "InternalLoanService >> Erro ao processar empréstimo pendente [id: ${internalLoanId}]"])
    }

    private BigDecimal getValueToBePaid(InternalLoan internalLoan, BigDecimal debtorNegativeBalance, BigDecimal guarantorPositiveBalance) {
        BigDecimal value = BigDecimalUtils.min(BigDecimalUtils.abs(debtorNegativeBalance), internalLoan.remainingValue)
        return BigDecimalUtils.min(value, BigDecimalUtils.abs(guarantorPositiveBalance))
    }

    private InternalLoan setAsCancelled(InternalLoan internalLoan) {
        internalLoan.status = InternalLoanStatus.CANCELLED
        internalLoan.save(failOnError: true)

        return internalLoan
    }

    private InternalLoan setAsPartiallyPaid(InternalLoan internalLoan) {
        internalLoan.remainingValue = 0
        internalLoan.status = InternalLoanStatus.PARTIALLY_PAID
        internalLoan.save(failOnError: true)

        return internalLoan
    }

    private InternalLoanItem saveItem(InternalLoan internalLoan, InternalTransfer internalTransfer) {
        InternalLoanItem item = new InternalLoanItem()
        item.internalLoan = internalLoan
        item.internalTransfer = internalTransfer
        item.value = internalTransfer.value
        item.save(failOnError: true)

        setAsPaidIfPossible(internalLoan)

        return item
    }

    private InternalLoan setAsPaidIfPossible(InternalLoan internalLoan) {
        BigDecimal totalValue = InternalLoanItem.sumValue([internalLoan: internalLoan]).get()

        if (internalLoan.value == totalValue) {
            internalLoan.remainingValue = 0
            internalLoan.status = InternalLoanStatus.PAID
            internalLoan.save(flush: true, failOnError: true)
        } else {
            internalLoan.remainingValue = internalLoan.value - totalValue
            internalLoan.save(failOnError: true)
        }

        return internalLoan
    }

    private void processInternalLoanWhenDebtorHasPositiveBalance(InternalLoan internalLoan) {
        if (internalLoan.hasItems()) {
            setAsPartiallyPaid(internalLoan)
        } else {
            setAsCancelled(internalLoan)
        }
    }
}
