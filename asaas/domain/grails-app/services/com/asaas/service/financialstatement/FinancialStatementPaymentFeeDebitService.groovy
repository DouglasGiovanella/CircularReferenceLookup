package com.asaas.service.financialstatement

import com.asaas.debit.DebitType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementPaymentFeeDebitService {

    def financialStatementService

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createStatementsForPaymentCreationFeeDebit(yesterday)
        }, [logErrorMessage: "FinancialStatementPaymentFeeDebitService.createStatementsForPaymentCreationFeeDebit >> Falha ao gerar os lançamentos de criação de taxas de pagamento."])

        Utils.withNewTransactionAndRollbackOnError({
            createStatementsForPaymentUpdateFeeDebit(yesterday)
        }, [logErrorMessage: "FinancialStatementPaymentFeeDebitService.createStatementsForPaymentUpdateFeeDebit >> Falha ao gerar os lançamentos de atualização de taxas de pagamento."])
    }

    private void createStatementsForPaymentCreationFeeDebit(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.PAYMENT_CREATION_FEE, [FinancialStatementType.PAYMENT_CREATION_FEE_REVENUE], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_CREATION_FEE_REVENUE],
            [financialStatementType: FinancialStatementType.PAYMENT_CREATION_FEE_EXPENSE]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createStatementsForPaymentUpdateFeeDebit(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(DebitType.PAYMENT_UPDATE_FEE, [FinancialStatementType.PAYMENT_UPDATE_FEE_REVENUE], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.PAYMENT_UPDATE_FEE_REVENUE],
            [financialStatementType: FinancialStatementType.PAYMENT_UPDATE_FEE_CUSTOMER_BALANCE_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateStatements(DebitType debitType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.DEBIT
        search.debitType = debitType
        search."financialStatementTypeList[notExists]" = statementTypeList
        search.transactionDate = transactionDate

        return FinancialTransaction.query(search).list()
    }
}
