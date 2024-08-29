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
class FinancialStatementIssService {

    def financialStatementService

    public void createForIssTax(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementIssService >> createForIssTax")

        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                "transactionType": FinancialTransactionType.DEBIT,
                "debitType": DebitType.ISS_TAX,
                "transactionDate[ge]": startDate,
                "transactionDate[lt]": endDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ISS_TAX_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.ISS_TAX_ASAAS_BALANCE_CREDIT, FinancialStatementType.ISS_TAX_ASAAS_BALANCE_PROVISION_DEBIT]
            ]).list()

            if (!transactionList) return

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            List<Map> financialStatementInfo = [
                [financialStatementType: FinancialStatementType.ISS_TAX_CUSTOMER_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.ISS_TAX_ASAAS_BALANCE_CREDIT]
            ]
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfo, santander)

            Bank bancoDoBrasil = Bank.query([code: SupportedBank.BANCO_DO_BRASIL.code()]).get()
            List<Map> financialStatementDebitProvisionInfo = [
                [financialStatementType: FinancialStatementType.ISS_TAX_ASAAS_BALANCE_PROVISION_DEBIT]
            ]
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementDebitProvisionInfo, bancoDoBrasil)
        }, [logErrorMessage: "FinancialStatementIssService.createForIssTax >>> Erro ao executar createForIssTax"])
    }
}
