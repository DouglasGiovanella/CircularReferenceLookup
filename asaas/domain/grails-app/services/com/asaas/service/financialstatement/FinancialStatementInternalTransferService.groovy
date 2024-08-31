package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.internaltransfer.InternalTransferType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementInternalTransferService {

    def financialStatementService

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createAsaasAccountInternalTransferDebitStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createAsaasAccountInternalTransferDebitStatements >> Falha ao gerar os lançamentos contábeis de débito de transferência entre contas Asaas."])

        Utils.withNewTransactionAndRollbackOnError({
            createAsaasAccountInternalTransferCreditStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createAsaasAccountInternalTransferCreditStatements >> Falha ao gerar os lançamentos contábeis de crédito de transferência entre contas Asaas."])

        Utils.withNewTransactionAndRollbackOnError({
            createAsaasAccountInternalTransferReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createAsaasAccountInternalTransferReversalStatements >> Falha ao gerar os lançamentos contábeis de estorno de transferência entre contas Asaas."])

        Utils.withNewTransactionAndRollbackOnError({
            createSplitInternalTransferDebitStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createSplitInternalTransferDebitStatements >> Falha ao gerar os lançamentos contábeis de débito de comissão entre cadastros."])

        Utils.withNewTransactionAndRollbackOnError({
            createSplitInternalTransferCreditStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createSplitInternalTransferCreditStatements >> Falha ao gerar os lançamentos contábeis de crédito de comissão entre cadastros."])

        Utils.withNewTransactionAndRollbackOnError({
            createSplitInternalTransferDebitRefundStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createSplitInternalTransferDebitRefundStatements >> Falha ao gerar os lançamentos contábeis de débito de estorno de comissão entre cadastros."])

        Utils.withNewTransactionAndRollbackOnError({
            createSplitInternalTransferCreditRefundStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createSplitInternalTransferCreditRefundStatements >> Falha ao gerar os lançamentos contábeis de crédito de estorno de comissão entre cadastros."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalLoanDebitStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createInternalLoanDebitStatements >> Falha ao gerar os lançamentos contábeis de débito de regularização de saldo P2P."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalLoanCreditStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createInternalLoanCreditStatements >> Falha ao gerar os lançamentos contábeis de crédito de regularização de saldo P2P."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalLoanPaymentDebitStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createInternalLoanPaymentDebitStatements >> Falha ao gerar os lançamentos contábeis de débito de devolução de saldo P2P."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalLoanPaymentCreditStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementInternalTransferService.createInternalLoanPaymentCreditStatements >> Falha ao gerar os lançamentos contábeis de crédito de devolução de saldo P2P."])
    }

    private void createAsaasAccountInternalTransferDebitStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, InternalTransferType.ASAAS_ACCOUNT, [FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_DEBIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_DEBIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createAsaasAccountInternalTransferCreditStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, InternalTransferType.ASAAS_ACCOUNT, [FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_CREDIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createAsaasAccountInternalTransferReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_REVERSAL, InternalTransferType.ASAAS_ACCOUNT, [FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_REVERSAL_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.ASAAS_ACCOUNT_INTERNAL_TRANSFER_REVERSAL_CREDIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createSplitInternalTransferDebitStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, InternalTransferType.SPLIT, [FinancialStatementType.SPLIT_INTERNAL_TRANSFER_DEBIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.SPLIT_INTERNAL_TRANSFER_DEBIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createSplitInternalTransferCreditStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, InternalTransferType.SPLIT, [FinancialStatementType.SPLIT_INTERNAL_TRANSFER_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.SPLIT_INTERNAL_TRANSFER_CREDIT]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createSplitInternalTransferDebitRefundStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, InternalTransferType.SPLIT_REVERSAL, [FinancialStatementType.SPLIT_INTERNAL_TRANSFER_DEBIT_REFUND], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.SPLIT_INTERNAL_TRANSFER_DEBIT_REFUND]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createSplitInternalTransferCreditRefundStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, InternalTransferType.SPLIT_REVERSAL, [FinancialStatementType.SPLIT_INTERNAL_TRANSFER_CREDIT_REFUND], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        Map financialStatementInfoMap = [financialStatementType: FinancialStatementType.SPLIT_INTERNAL_TRANSFER_CREDIT_REFUND]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createInternalLoanDebitStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, InternalTransferType.INTERNAL_LOAN, [FinancialStatementType.INTERNAL_LOAN_INTERNAL_TRANSFER_DEBIT], transactionDate)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_LOAN_INTERNAL_TRANSFER_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createInternalLoanCreditStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, InternalTransferType.INTERNAL_LOAN, [FinancialStatementType.INTERNAL_LOAN_INTERNAL_TRANSFER_CREDIT], transactionDate)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_LOAN_INTERNAL_TRANSFER_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createInternalLoanPaymentDebitStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, InternalTransferType.INTERNAL_LOAN_PAYMENT, [FinancialStatementType.INTERNAL_LOAN_PAYMENT_INTERNAL_TRANSFER_DEBIT], transactionDate)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_LOAN_PAYMENT_INTERNAL_TRANSFER_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private void createInternalLoanPaymentCreditStatements(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, InternalTransferType.INTERNAL_LOAN_PAYMENT, [FinancialStatementType.INTERNAL_LOAN_PAYMENT_INTERNAL_TRANSFER_CREDIT], transactionDate)
        if (!financialTransactionList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_LOAN_PAYMENT_INTERNAL_TRANSFER_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", financialTransactionList, financialStatementInfoMapList, bank)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateStatements(FinancialTransactionType transactionType, InternalTransferType internalTransferType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = transactionType
        search.internalTransferType = internalTransferType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = transactionDate

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
