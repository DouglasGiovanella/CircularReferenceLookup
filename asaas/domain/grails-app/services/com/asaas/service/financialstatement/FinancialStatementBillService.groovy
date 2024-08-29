package com.asaas.service.financialstatement

import com.asaas.bill.BillStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementBillService {

    def financialStatementService

    public void createFinancialStatementsForBill(Date yesterday) {
        AsaasLogger.info("FinancialStatementBillService >> createTransitoryForScheduledBillPayment")
        createTransitoryForScheduledBillPayment(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createTransitoryForReversedBillPayment")
        createTransitoryForReversedBillPayment(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createTransitoryForPaidBillPayment")
        createTransitoryForPaidBillPayment(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createForBillPaymentExpense")
        createForBillPaymentExpense(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> refundBillPaymentExpense")
        refundBillPaymentExpense(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createForBillPaymentFee")
        createForBillPaymentFee(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createForBillPaymentFeeDiscount")
        createForBillPaymentFeeDiscount(yesterday)
        AsaasLogger.info("FinancialStatementBillService >> createForFailedBillPaymentFee")
        createForFailedBillPaymentFee(yesterday)

        Utils.withNewTransactionAndRollbackOnError({
            createInternalBillPaymentStatements(yesterday)
        }, [logErrorMessage: "FinancialStatementBillService.createInternalBillPaymentStatements >> Falha ao gerar os lançamentos contábeis de pague contas com boleto interno."])
    }

    private void createForBillPaymentExpense(Date billPaymentDate) {
        Utils.withNewTransactionAndRollbackOnError({
             List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT,
                billStatusList: [BillStatus.PAID, BillStatus.REFUNDED],
                billPaymentDate: billPaymentDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_PAYMENT_EXPENSE],
                "billFinancialStatementList[notExists]": FinancialStatementType.BILL_PAYMENT_EXPENSE
            ]).list(readOnly: true)

            financialTransactionList = financialTransactionList.findAll { !it.bill.asaasBankSlip }

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.BILL_PAYMENT_EXPENSE]
            ]

            Map<Bank, List<FinancialTransaction>> financialTransactionListGroupedByBank = financialTransactionList.groupBy { it.bill.paymentBank }

            financialTransactionListGroupedByBank.each { Bank bank, List<FinancialTransaction> financialTransactionListByBank ->
                financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionListByBank, financialStatementInfoList, bank)
            }
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createForBillPaymentExpense"])
    }

    private void createForBillPaymentFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_FEE_REVENUE, FinancialStatementType.BILL_FEE_EXPENSE],
                "billFinancialStatementList[notExists]": [FinancialStatementType.BILL_FEE_REVENUE, FinancialStatementType.BILL_FEE_EXPENSE]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.BILL_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.BILL_FEE_EXPENSE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createForBillPaymentFee"])
    }

    private void createForBillPaymentFeeDiscount(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                "bill[isNotNull]": true,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_FEE_DISCOUNT_CREDIT, FinancialStatementType.BILL_FEE_DISCOUNT_EXPENSE],
                "billFeeDiscountFinancialStatement[notExists]": true
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.BILL_FEE_DISCOUNT_CREDIT],
                [financialStatementType: FinancialStatementType.BILL_FEE_DISCOUNT_EXPENSE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createForBillPaymentFeeDiscount"])
    }

    private void createForFailedBillPaymentFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT_FEE_CANCELLED,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.FAILED_BILL_PAYMENT_FEE_REVENUE, FinancialStatementType.FAILED_BILL_PAYMENT_FEE_EXPENSE],
                "billFinancialStatementList[notExists]": [FinancialStatementType.FAILED_BILL_PAYMENT_FEE_REVENUE, FinancialStatementType.FAILED_BILL_PAYMENT_FEE_EXPENSE]
            ]).list()

            if (!financialTransactionList) return

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.FAILED_BILL_PAYMENT_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.FAILED_BILL_PAYMENT_FEE_EXPENSE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createForFailedBillPaymentFee"])
    }

    private void createTransitoryForPaidBillPayment(Date billPaymentDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT,
                billStatusList: [BillStatus.PAID, BillStatus.REFUNDED],
                billPaymentDate: billPaymentDate,
                "financialStatementTypeList[exists]": [FinancialStatementType.BILL_PAYMENT_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_PAYMENT_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.bill.paymentDate }

            financialTransactionListGroupedByDate.each { Date paymentDate, List<FinancialTransaction> financialTransactionListByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.BILL_PAYMENT_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT, paymentDate, null, financialTransactionListByDate.value.sum())
                financialStatementService.saveItems(financialStatement, financialTransactionListByDate)
            }
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createTransitoryForPaidBillPayment"])
    }

    private void createTransitoryForReversedBillPayment(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT_CANCELLED,
                transactionDate: transactionDate,
                "billFinancialStatementList[exists]": [FinancialStatementType.BILL_PAYMENT_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_PAYMENT_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT]]).list(readOnly: true)

            if (!financialTransactionList) return

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.BILL_PAYMENT_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createTransitoryForReversedBillPayment"])
    }

    private void createTransitoryForScheduledBillPayment(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.transactionDateDifferentFromBillPaymentDate([
                transactionType: FinancialTransactionType.BILL_PAYMENT,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.BILL_PAYMENT_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.BILL_PAYMENT_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar createTransitoryForScheduledBillPayment"])
    }

    private void refundBillPaymentExpense(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.BILL_PAYMENT_REFUNDED,
                billStatus: BillStatus.REFUNDED,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_BILL_PAYMENT_EXPENSE],
                "billFinancialStatementList[notExists]": FinancialStatementType.REFUND_BILL_PAYMENT_EXPENSE]
            ).list(readOnly: true)

            financialTransactionList = financialTransactionList.findAll { !it.bill.asaasBankSlip }

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.REFUND_BILL_PAYMENT_EXPENSE]
            ]

            Map<Bank, List<FinancialTransaction>> financialTransactionListGroupedByBank = financialTransactionList.groupBy { it.bill.paymentBank }

            financialTransactionListGroupedByBank.each { Bank bank, List<FinancialTransaction> financialTransactionListByBank ->
                financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionListByBank, financialStatementInfoList, bank)
            }
        }, [logErrorMessage: "FinancialStatementBillService - Erro ao executar refundBillPaymentExpense"])
    }

    private void createInternalBillPaymentStatements(Date transactionDate) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.BILL_PAYMENT,
            billStatusList: [BillStatus.PAID, BillStatus.REFUNDED],
            billAsaasBankSlip: true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.INTERNAL_BILL_PAYMENT_DEBIT],
            transactionDate: transactionDate
        ]).list(readOnly: true)
        if (!transactionList) return

        Bank bank = Bank.query([code: SupportedBank.ASAAS.code()]).get()
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.INTERNAL_BILL_PAYMENT_DEBIT],
            [financialStatementType: FinancialStatementType.INTERNAL_BILL_PAYMENT_CREDIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }
}
