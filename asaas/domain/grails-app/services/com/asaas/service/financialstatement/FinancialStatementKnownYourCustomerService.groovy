package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementKnownYourCustomerService {

    def financialStatementService

    public void createFinancialStatements(Date date) {
        AsaasLogger.info("FinancialStatementKnownYourCustomerService >> createFinancialStatements")

        Utils.withNewTransactionAndRollbackOnError({
            Map search = [:]
            search.transactionType = FinancialTransactionType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH_FEE
            search.transactionDate = date
            search."financialStatementTypeList[notExists]" = [FinancialStatementType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH_FEE_REVENUE, FinancialStatementType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH_FEE_CUSTOMER_BALANCE_DEBIT]

            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(search).list()
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = []
            financialStatementInfoList.add([financialStatementType: FinancialStatementType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH_FEE_REVENUE])
            financialStatementInfoList.add([financialStatementType: FinancialStatementType.CHILD_ACCOUNT_KNOWN_YOUR_CUSTOMER_BATCH_FEE_CUSTOMER_BALANCE_DEBIT])

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementKnownYourCustomerService - Erro ao executar createFinancialStatements"])
    }
}
