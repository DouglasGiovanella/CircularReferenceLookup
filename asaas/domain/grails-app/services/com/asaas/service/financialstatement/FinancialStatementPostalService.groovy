package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementPostalService {

    def financialStatementService

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createPostalServiceFeeStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementPostalService.createPostalServiceFeeStatements >> Falha ao gerar os lançamentos contábeis de taxa para envio via Correios."])

        Utils.withNewTransactionAndRollbackOnError({
            createPostalServiceFeePromotionalCodeStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementPostalService.createPostalServiceFeePromotionalCodeStatements >> Falha ao gerar os lançamentos contábeis de desconto na taxa para envio via Correios."])
    }

    private void createPostalServiceFeeStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.POSTAL_SERVICE_FEE, [FinancialStatementType.POSTAL_SERVICE_FEE_REVENUE], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.POSTAL_SERVICE_FEE_REVENUE],
            [financialStatementType: FinancialStatementType.POSTAL_SERVICE_FEE_EXPENSE]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createPostalServiceFeePromotionalCodeStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = listFinancialTransactionsToCreateStatements(FinancialTransactionType.PROMOTIONAL_CODE_CREDIT, [FinancialStatementType.POSTAL_SERVICE_FEE_PROMOTIONAL_CODE_CUSTOMER_BALANCE_CREDIT], transactionDate)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.POSTAL_SERVICE_FEE_PROMOTIONAL_CODE_ASAAS_BALANCE_DEBIT],
            [financialStatementType: FinancialStatementType.POSTAL_SERVICE_FEE_PROMOTIONAL_CODE_CUSTOMER_BALANCE_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private List<FinancialTransaction> listFinancialTransactionsToCreateStatements(FinancialTransactionType transactionType, List<FinancialStatementType> statementTypeList, Date transactionDate) {
        Map search = [:]
        search.transactionType = transactionType
        search.transactionDate = transactionDate
        search."financialStatementTypeList[notExists]" = statementTypeList
        search."paymentPostalServiceBatch[isNotNull]" = true

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
