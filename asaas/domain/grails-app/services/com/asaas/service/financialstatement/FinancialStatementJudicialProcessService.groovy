package com.asaas.service.financialstatement

import com.asaas.debit.DebitType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementJudicialProcessService {

    def financialStatementService

    public void createFinancialStatementsForJudicialProcess(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementJudicialProcessService >> createForJudicialProcess")
        createForJudicialProcess(startDate, endDate)
    }

    private void createForJudicialProcess(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                'transactionType': FinancialTransactionType.DEBIT,
                'debitType': DebitType.JUDICIAL_PROCESS,
                'dateCreated[ge]': startDate,
                'dateCreated[lt]': endDate,
                'financialStatementTypeList[notExists]': [FinancialStatementType.JUDICIAL_PROCESS_ASAAS_BALANCE_CREDIT, FinancialStatementType.JUDICIAL_PROCESS_CUSTOMER_BALANCE_DEBIT]
            ]).list()
            if (!transactionList) return

            Bank santander = Bank.query(code: SupportedBank.SANTANDER.code()).get()
            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_ASAAS_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.JUDICIAL_PROCESS_CUSTOMER_BALANCE_DEBIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSave('transactionDate', transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementJudicialProcessService - Erro ao executar createForJudicialProcess"])
    }
}
