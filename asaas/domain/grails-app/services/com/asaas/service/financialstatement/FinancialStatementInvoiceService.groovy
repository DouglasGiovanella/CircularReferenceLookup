package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementInvoiceService {

    def financialStatementService

    public void createFinancialStatementsForInvoice(Date date) {
        AsaasLogger.info("FinancialStatementInvoiceService >> createFinancialStatementsForInvoiceAuthorizationFee")
        createFinancialStatementsForInvoiceAuthorizationFee(date)
        AsaasLogger.info("FinancialStatementInvoiceService >> createFinancialStatementsForInvoiceAuthorizationFeeDiscount")
        createFinancialStatementsForInvoiceAuthorizationFeeDiscount(date)
        AsaasLogger.info("FinancialStatementInvoiceService >> createFinancialStatementsForProductInvoiceFee")
        createFinancialStatementsForProductInvoiceFee(date)
        AsaasLogger.info("FinancialStatementInvoiceService >> createFinancialStatementsForConsumerInvoiceFee")
        createFinancialStatementsForConsumerInvoiceFee(date)
    }

    private void createFinancialStatementsForInvoiceAuthorizationFee(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.INVOICE_FEE,
                transactionDate: date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.INVOICE_AUTHORIZATION_FEE_EXPENSE, FinancialStatementType.INVOICE_AUTHORIZATION_FEE_REVENUE]
            ]).list()

            if (!financialTransactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            FinancialStatement invoiceAuthorizationFeeExpense = financialStatementService.save(FinancialStatementType.INVOICE_AUTHORIZATION_FEE_EXPENSE, date, bank, financialTransactionList.value.sum())
            financialStatementService.saveItems(invoiceAuthorizationFeeExpense, financialTransactionList)

            FinancialStatement invoiceAuthorizationFeeRevenue = financialStatementService.save(FinancialStatementType.INVOICE_AUTHORIZATION_FEE_REVENUE, date, bank, financialTransactionList.value.sum())
            financialStatementService.saveItems(invoiceAuthorizationFeeRevenue, financialTransactionList)
        }, [logErrorMessage: "FinancialStatementInvoiceService - Erro ao executar createFinancialStatementsForInvoiceAuthorizationFee"])
    }

    private void createFinancialStatementsForInvoiceAuthorizationFeeDiscount(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                transactionDate: date,
                "invoice[isNotNull]": true,
                "financialStatementTypeList[notExists]": [FinancialStatementType.INVOICE_AUTHORIZATION_FEE_DISCOUNT_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.INVOICE_AUTHORIZATION_FEE_DISCOUNT_EXPENSE, FinancialStatementType.INVOICE_AUTHORIZATION_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
            ]).list()
            if (!financialTransactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.INVOICE_AUTHORIZATION_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.INVOICE_AUTHORIZATION_FEE_DISCOUNT_EXPENSE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementInvoiceService - Erro ao executar createFinancialStatementsForInvoiceAuthorizationFeeDiscount"])
    }

    private void createFinancialStatementsForProductInvoiceFee(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            Map queryParameters = [:]
            queryParameters.transactionType = FinancialTransactionType.PRODUCT_INVOICE_FEE
            queryParameters.transactionDate = date
            queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.PRODUCT_INVOICE_FEE_EXPENSE, FinancialStatementType.PRODUCT_INVOICE_FEE_REVENUE]
            List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list()
            if (!transactionList) return

            List<Map> financialStatementInfoList = []
            financialStatementInfoList.add([financialStatementType: FinancialStatementType.PRODUCT_INVOICE_FEE_EXPENSE])
            financialStatementInfoList.add([financialStatementType: FinancialStatementType.PRODUCT_INVOICE_FEE_REVENUE])

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementInvoiceService - Erro ao executar createFinancialStatementsForProductInvoiceFee"])
    }

    private void createFinancialStatementsForConsumerInvoiceFee(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            Map queryParameters = [:]

            queryParameters.transactionType = FinancialTransactionType.CONSUMER_INVOICE_FEE
            queryParameters.transactionDate = date
            queryParameters."financialStatementTypeList[notExists]" = [
                FinancialStatementType.CONSUMER_INVOICE_FEE_EXPENSE,
                FinancialStatementType.CONSUMER_INVOICE_FEE_REVENUE
            ]

            List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CONSUMER_INVOICE_FEE_EXPENSE],
                [financialStatementType: FinancialStatementType.CONSUMER_INVOICE_FEE_REVENUE]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementInvoiceService.createFinancialStatementsForConsumerInvoiceFee >>> Erro ao criar demonstrativo financeiro de nota fiscal de consumidor para a data [${date}]"])
    }
}
