package com.asaas.service.customerdebtappropriation

import com.asaas.credit.CreditType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDebtAppropriationFinancialStatementService {

    def financialStatementService

    public void create() {
        Utils.withNewTransactionAndRollbackOnError({
            Date transactionDate = CustomDateUtils.getYesterday()

            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CREDIT,
                creditType: CreditType.CUSTOMER_DEBT_APPROPRIATION,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CUSTOMER_DEBT_APPROPRIATION_CREDIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CUSTOMER_DEBT_APPROPRIATION_DEBIT],
                [financialStatementType: FinancialStatementType.CUSTOMER_DEBT_APPROPRIATION_CREDIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "CustomerDebtAppropriationFinancialStatementService.create >> Erro ao criar lançamentos de apropriação de débito"])
    }
}
