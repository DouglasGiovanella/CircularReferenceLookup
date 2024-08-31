package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementDebtRecoveryNegotiationPaymentService {

    def financialStatementService
    def grailsApplication

    public void createAllStatements() {
        Date yesterday = CustomDateUtils.getYesterday()

        Utils.withNewTransactionAndRollbackOnError({
            createFinancialChargesForAsaasDebtRecoveryAccount(yesterday)
        }, [logErrorMessage: "FinancialStatementDebtRecoveryNegotiationPaymentService.createFinancialChargesExpenseForAsaasDebtRecoveryAccount >> Falha ao gerar os lançamentos financeiros de encargos de renegociação de saldo negativo de cliente."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalTransferDebitForAsaasAccount(yesterday)
        }, [logErrorMessage: "FinancialStatementDebtRecoveryNegotiationPaymentService.createInternalTransferDebitForAsaasAccount >> Falha ao gerar os lançamentos financeiros de débito de transferência entre cadastros."])

        Utils.withNewTransactionAndRollbackOnError({
            createInternalTransferCreditForCustomerAccount(yesterday)
        }, [logErrorMessage: "FinancialStatementDebtRecoveryNegotiationPaymentService.createInternalTransferCreditForCustomerAccount >> Falha ao gerar os lançamentos financeiros de crédito de transferência entre cadastros."])
    }

    private void createFinancialChargesForAsaasDebtRecoveryAccount(Date transactionDate) {
        Map search = [:]
        search."transactionType" = FinancialTransactionType.DEBT_RECOVERY_NEGOTIATION_FINANCIAL_CHARGES
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.DEBT_RECOVERY_FINANCIAL_CHARGES_EXPENSE]
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)

        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!financialTransactionList) return

        List<Map> financialStatementTypeInfoList = [
            [financialStatementType: FinancialStatementType.DEBT_RECOVERY_FINANCIAL_CHARGES_EXPENSE],
            [financialStatementType: FinancialStatementType.DEBT_RECOVERY_FINANCIAL_CHARGES_REVENUE]
        ]

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementTypeInfoList, santander)
    }

    private void createInternalTransferDebitForAsaasAccount(Date transactionDate) {
        Map search = [provider: Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)]

        List<FinancialTransaction> financialTransactionList = listInternalTransferForDebtRecoveryTransaction(FinancialTransactionType.INTERNAL_TRANSFER_DEBIT, [FinancialStatementType.DEBT_RECOVERY_INTERNAL_TRANSFER_DEBIT], search, transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementTypeInfoList = [
            [financialStatementType: FinancialStatementType.DEBT_RECOVERY_INTERNAL_TRANSFER_DEBIT]
        ]

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementTypeInfoList, santander)
    }

    private void createInternalTransferCreditForCustomerAccount(Date transactionDate) {
        Map search = [internalTransferCustomer: Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)]

        List<FinancialTransaction> financialTransactionList = listInternalTransferForDebtRecoveryTransaction(FinancialTransactionType.INTERNAL_TRANSFER_CREDIT, [FinancialStatementType.DEBT_RECOVERY_INTERNAL_TRANSFER_CREDIT], search, transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementTypeInfoList = [
            [financialStatementType: FinancialStatementType.DEBT_RECOVERY_INTERNAL_TRANSFER_CREDIT]
        ]

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementTypeInfoList, santander)
    }

    private List<FinancialTransaction> listInternalTransferForDebtRecoveryTransaction(FinancialTransactionType financialTransactionType, List<FinancialStatementType> financialStatementTypeList, Map search, Date transactionDate) {
        search.transactionType = financialTransactionType
        search."debtRecoveryNegotiationPayment[exists]" = true
        search.transactionDate = CustomDateUtils.formatDate(transactionDate)
        search."financialStatementTypeList[notExists]" = financialStatementTypeList

        return FinancialTransaction.query(search).list(readOnly: true)
    }
}
