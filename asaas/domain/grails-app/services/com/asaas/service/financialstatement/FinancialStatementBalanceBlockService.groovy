package com.asaas.service.financialstatement

import com.asaas.debit.DebitType
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementBalanceBlockService {

    def financialStatementService

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createJudicialProcessManualBalanceBlockStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createJudicialProcessManualBalanceBlockStatements >> Falha ao gerar os lançamentos contábeis/financeiros de bloqueio judicial de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createJudicialProcessBalanceManualBlockReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createJudicialProcessBalanceManualBlockReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de desbloqueio judicial de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createJudicialProcessBalanceBlockStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createJudicialProcessBalanceBlockStatements >> Falha ao gerar os lançamentos contábeis/financeiros de bloqueio judicial de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createJudicialProcessBalanceBlockReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createJudicialProcessBalanceBlockReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de desbloqueio judicial de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createManualBalanceBlockStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createManualBalanceBlockStatements >> Falha ao gerar os lançamentos contábeis/financeiros de bloqueio de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createManualBalanceBlockReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBalanceBlockService.createManualBalanceBlockReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de desbloqueio de saldo."])
    }

    private void createJudicialProcessManualBalanceBlockStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockStatements(DebitType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK, [FinancialStatementType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createJudicialProcessBalanceManualBlockReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockReversalStatements(DebitType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK, [FinancialStatementType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK_REVERSAL], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_MANUAL_BALANCE_BLOCK_REVERSAL]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createManualBalanceBlockStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockStatements(DebitType.BALANCE_BLOCK, [FinancialStatementType.MANUAL_BALANCE_BLOCK], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.MANUAL_BALANCE_BLOCK]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createManualBalanceBlockReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockReversalStatements(DebitType.BALANCE_BLOCK, [FinancialStatementType.MANUAL_BALANCE_BLOCK_REVERSAL], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.MANUAL_BALANCE_BLOCK_REVERSAL]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createJudicialProcessBalanceBlockStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockStatements(FinancialTransactionType.BACEN_JUDICIAL_LOCK, [FinancialStatementType.JUDICIAL_PROCESS_BALANCE_BLOCK], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_BALANCE_BLOCK]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createJudicialProcessBalanceBlockReversalStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateBlockStatements(FinancialTransactionType.BACEN_JUDICIAL_UNLOCK, [FinancialStatementType.JUDICIAL_PROCESS_BALANCE_BLOCK_REVERSAL], transactionDate)
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_BALANCE_BLOCK_REVERSAL]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateBlockStatements(DebitType debitType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.DEBIT
        search.debitType = debitType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        return FinancialTransaction.query(search).list(readOnly: true)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateBlockReversalStatements(DebitType debitType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.DEBIT_REVERSAL
        search.debitType = debitType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        return FinancialTransaction.query(search).list(readOnly: true)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateBlockStatements(FinancialTransactionType financialTransactionType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = financialTransactionType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
