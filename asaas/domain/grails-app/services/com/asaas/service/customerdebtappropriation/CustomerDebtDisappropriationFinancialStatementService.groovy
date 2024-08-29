package com.asaas.service.customerdebtappropriation

import com.asaas.debit.DebitType
import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDebtDisappropriationFinancialStatementService {

    def financialStatementService
    def financialStatementItemService

    public void create() {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = listCustomerDebtDisappropriationTransactions(new Date().clearTime(), null)
            if (!transactionList) return

            save(transactionList)
        }, [logErrorMessage: "CustomerDebtDisappropriationFinancialStatementService.create >> Erro ao criar lançamentos de desapropriação de débito"])
    }

    public void createForSpecificCustomerAndDate(Customer customer, Date transactionDate) {
        try {
            List<FinancialTransaction> transactionList = listCustomerDebtDisappropriationTransactions(transactionDate, customer)
            if (!transactionList)

            save(transactionList)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerDebtDisappropriationFinancialStatementService.createForSpecificCustomerAndDate >> Erro ao criar lançamentos de desapropriação de débito", exception)
        }
    }

    private List<FinancialTransaction> listCustomerDebtDisappropriationTransactions(Date transactionDate, Customer customer) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.DEBIT
        search.debitType = DebitType.CUSTOMER_DEBT_DISAPPROPRIATION
        search.transactionDate = transactionDate
        search."debtDisappropriationFinancialStatementItem[notExists]" = true

        if (customer) search.provider = customer

        return FinancialTransaction.query(search).list(readOnly: true)
    }

    private void save(List<FinancialTransaction> transactionList) {
        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
        BigDecimal totalDisappropriatedValue = transactionList.value.sum().abs()

        FinancialStatementType debitType = FinancialStatementType.CUSTOMER_DEBT_DISAPPROPRIATION_DEBIT
        FinancialStatement debitFinancialStatement = financialStatementService.save(debitType, new Date().clearTime(), bank, totalDisappropriatedValue)

        FinancialStatementType creditType = FinancialStatementType.CUSTOMER_DEBT_DISAPPROPRIATION_CREDIT
        FinancialStatement creditFinancialStatement = financialStatementService.save(creditType, new Date().clearTime(), bank, totalDisappropriatedValue)

        for (FinancialTransaction financialTransaction : transactionList) {
            financialStatementItemService.save(debitFinancialStatement, financialTransaction)
            financialStatementItemService.save(creditFinancialStatement, financialTransaction)
        }
    }
}
