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
class FinancialStatementIrService {

    def financialStatementService

    public void createForIrTax(Date transactionDate) {
        AsaasLogger.info("FinancialStatementIrService >> createForIrTax")

        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.DEBIT,
                debitType: DebitType.IR_TAX,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.IR_TAX_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            List<Map> financialStatementInfo = [
                [financialStatementType: FinancialStatementType.IR_TAX_CUSTOMER_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.IR_TAX_ASAAS_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.IR_TAX_ASAAS_BALANCE_PROVISION_DEBIT]
            ]
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfo, santander)
        }, [logErrorMessage: "FinancialStatementIrService.createForIrTax >>> Erro ao executar createForIrTax"])
    }
}
