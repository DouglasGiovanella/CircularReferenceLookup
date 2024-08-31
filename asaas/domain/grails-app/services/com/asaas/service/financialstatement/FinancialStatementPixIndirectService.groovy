package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.pix.PixTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.springframework.util.StopWatch

@Transactional
class FinancialStatementPixIndirectService {

    def financialStatementService

    public void createFinancialStatements(Date transactionDate) {
        if (!transactionDate) throw new RuntimeException("A data de transação deve ser informada")

        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementPixIndirectService.createFinancialStatements() [início: ${new Date()}]")

        StopWatch stopWatch = new StopWatch("FinancialStatementPixIndirectService")

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForPaymentReceivedWithPixIndirect")
            createForPaymentReceivedWithPixIndirect(transactionDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementPixIndirectService.createForPaymentReceivedWithPixIndirect() -> Erro ao executar createForPaymentReceivedWithPixIndirect."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("transactionsWithoutFeeRevenueStatementsForReceivedPaymentsWithPixIndirect")
            transactionsWithoutFeeRevenueStatementsForReceivedPaymentsWithPixIndirect(transactionDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementPixIndirectService.transactionsWithoutFeeRevenueStatementsForReceivedPaymentsWithPixIndirect() -> Erro ao executar transactionsWithoutFeeRevenueStatementsForReceivedPaymentsWithPixIndirect."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("createForRefundedPaymentReceivedWithPixIndirect")
            createForRefundedPaymentReceivedWithPixIndirect(transactionDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementPixIndirectService.createForRefundedPaymentReceivedWithPixIndirect() -> Erro ao executar createForRefundedPaymentReceivedWithPixIndirect."])

        Utils.withNewTransactionAndRollbackOnError({
            stopWatch.start("transactionsWithoutStatementsAsaasExpenseFee")
            transactionsWithoutStatementsAsaasExpenseFee(transactionDate)
            stopWatch.stop()
        }, [logErrorMessage: "FinancialStatementPixIndirectService.transactionsWithoutStatementsAsaasExpenseFee() -> Erro ao executar transactionsWithoutStatementsAsaasExpenseFee."])

        AsaasLogger.info(stopWatch.prettyPrint())
        AsaasLogger.info("createPixFinancialStatements >> FinancialStatementPixIndirectService.createFinancialStatements() [conclusão: ${new Date()}]")
    }

    private void createForPaymentReceivedWithPixIndirect(Date transactionDate) {
        Map queryParameters = [
            transactionDate: transactionDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_CUSTOMER_REVENUE, FinancialStatementType.PIX_PLAN_REVENUE, FinancialStatementType.PIX_ASAAS_ERP_REVENUE],
            paymentBillingType: BillingType.PIX,
            transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
            "hasCreditPixTransactionWithAsaasKey[exists]": true,
            "cashInRiskAnalysisRequestReason[notExists]": true
        ]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
                                                    [financialStatementType: FinancialStatementType.PIX_CUSTOMER_REVENUE]
                                               ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private void transactionsWithoutFeeRevenueStatementsForReceivedPaymentsWithPixIndirect(Date transactionDate) {
        Map queryParameters = [transactionType: FinancialTransactionType.PAYMENT_FEE,
                               paymentBillingType: BillingType.PIX,
                               transactionDate: transactionDate,
                               "paymentFeeStatement[notExists]": true,
                               "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_PAYMENT_FEE_REVENUE]]

        List<FinancialTransaction> asaasKeyTransactionsWithoutFeeTransactionList = FinancialTransaction.query(queryParameters + ["hasCreditPixTransactionWithAsaasKey[exists]": true]).list(readOnly: true)
        if (!asaasKeyTransactionsWithoutFeeTransactionList) return

        List<Map> financialStatementTypeInfoList = [
            [financialStatementType: FinancialStatementType.PIX_PAYMENT_FEE_REVENUE],
            [financialStatementType: FinancialStatementType.PIX_PAYMENT_FEE_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", asaasKeyTransactionsWithoutFeeTransactionList, financialStatementTypeInfoList, null)
    }

    private void createForRefundedPaymentReceivedWithPixIndirect(Date transactionDate) {
        Map queryParameters = [transactionDate: transactionDate,
                               "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_CUSTOMER_REVENUE_REVERSAL],
                               paymentBillingType: BillingType.PIX,
                               transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
                               "hasCreditPixTransactionWithAsaasKey[exists]": true]
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(queryParameters).list(readOnly: true)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoList = [
            [financialStatementType: FinancialStatementType.PIX_CUSTOMER_REVENUE_REVERSAL]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, null)
    }

    private void transactionsWithoutStatementsAsaasExpenseFee(Date transactionDate) {
        Map search = [
            transactionType: FinancialTransactionType.PAYMENT_FEE,
            transactionDate: transactionDate,
            "hasPixTransactionPayment[exists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PIX_PAYMENT_FEE_ASAAS_EXPENSE]
        ]

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!transactionList) return

        createFeeAsaasExpenseStatementsForFeeTransactions(transactionList)
    }

    private void createFeeAsaasExpenseStatementsForFeeTransactions(List<FinancialTransaction> transactionList) {
        if (!transactionList) return

        BigDecimal totalExpense = (PixTransaction.RECEIVED_WITH_ASAAS_KEY_FEE * transactionList.size())

        final Integer daysToExpenseBeCharged = 2
        Date transactionDate = transactionList.first().transactionDate.clone()
        Date statementDate = CustomDateUtils.addBusinessDays(transactionDate, daysToExpenseBeCharged)

        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.PIX_PAYMENT_FEE_ASAAS_EXPENSE,
            statementDate,
            null,
            totalExpense)

        financialStatementService.saveItems(financialStatement, transactionList)
    }
}
