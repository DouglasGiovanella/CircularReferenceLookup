package com.asaas.service.financialstatement

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementPaymentCustodyService {

    def financialStatementService

    public void createStatements(Date yesterday) {
        Utils.withNewTransactionAndRollbackOnError({
            createPaymentCustodyBlockStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementPaymentCustodyService.createPaymentCustodyBlockStatements >> Falha ao gerar os lançamentos contábeis/financeiros de bloqueio judicial de saldo."])

        Utils.withNewTransactionAndRollbackOnError({
            createPaymentCustodyBlockReversalStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementPaymentCustodyService.createPaymentCustodyBlockReversalStatements >> Falha ao gerar os lançamentos contábeis/financeiros de desbloqueio judicial de saldo."])
    }

    private void createPaymentCustodyBlockStatements(Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.PAYMENT_CUSTODY_BLOCK
        search."financialStatementTypeList[notExists]" = FinancialStatementType.PAYMENT_CUSTODY_BLOCK
        search.transactionDate = transactionDate

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list()
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_CUSTODY_BLOCK]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }

    private void createPaymentCustodyBlockReversalStatements(Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.PAYMENT_CUSTODY_BLOCK_REVERSAL
        search."financialStatementTypeList[notExists]" = FinancialStatementType.PAYMENT_CUSTODY_BLOCK_REVERSAL
        search."reversedFinancialStatementType[exists]" = FinancialStatementType.PAYMENT_CUSTODY_BLOCK
        search.transactionDate = transactionDate

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list()
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_CUSTODY_BLOCK_REVERSAL]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, null)
    }
}
