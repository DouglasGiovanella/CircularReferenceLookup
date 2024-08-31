package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.payment.PaymentStatus
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementRefundBankSlipService {

    def financialStatementRefundService
    def financialStatementService

    public void createAllStatements() {
        Date transactionDate = CustomDateUtils.sumDays(new Date(), -1)

        createFinancialStatementsForRefundRequestFee(transactionDate)
        createFinancialStatementsForRefundRequestFeePaid(transactionDate)
        createFinancialStatementsForRefundRequestExpenseExpiredOrCancelled(transactionDate)
        createFinancialStatementsForRefundRequestFeeExpiredOrCancelled(transactionDate)
        createFinancialStatementsForRefundRequestFeeDiscount(transactionDate)

        financialStatementRefundService.createForBankSlipAndDebitCardPaymentReversal(transactionDate)
        financialStatementRefundService.createForBankSlipAndDebitCardPaymentReversalPaid(transactionDate)
    }

    public void createFinancialStatementsForRefundRequestFeePaid(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.REFUND_REQUEST_FEE,
                          refundRequestPaymentBillingType: BillingType.BOLETO,
                          refundRequestPaymentStatus: PaymentStatus.REFUNDED,
                          refundRequestStatus: RefundRequestStatus.PAID,
                          refundPaymentDate: transactionDate,
                          "refundRequestFeeFinancialStatementDebitType[exists]": true,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT_REVERSAL, FinancialStatementType.REFUND_REQUEST_FEE_EXPENSE, FinancialStatementType.REFUND_REQUEST_FEE_REVENUE]]
            List<FinancialTransaction> refundRequestFeeTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!refundRequestFeeTransactionList) return

            financialStatementRefundService.saveFinancialStatementAndItems(refundRequestFeeTransactionList, FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT_REVERSAL, transactionDate, null)

            Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
            financialStatementRefundService.saveFinancialStatementAndItems(refundRequestFeeTransactionList, FinancialStatementType.REFUND_REQUEST_FEE_EXPENSE, transactionDate, bank)
            financialStatementRefundService.saveFinancialStatementAndItems(refundRequestFeeTransactionList, FinancialStatementType.REFUND_REQUEST_FEE_REVENUE, transactionDate, bank)
        }, [logErrorMessage: "FinancialStatementRefundBankSlipService - Erro ao executar createFinancialStatementsForRefundRequestPaid"])
    }

    private void createFinancialStatementsForRefundRequestFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.REFUND_REQUEST_FEE,
                          refundRequestPaymentBillingType: BillingType.BOLETO,
                          transactionDate: transactionDate,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT]]
            List<FinancialTransaction> refundRequestFeeTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!refundRequestFeeTransactionList) return

            List<Map> financialStatementInfoMapList = [
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT],
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundRequestFeeTransactionList, financialStatementInfoMapList, null)
        }, [logErrorMessage: "FinancialStatementRefundBankSlipService - Erro ao executar createFinancialStatementsForRefundRequestFee"])
    }

    private void createFinancialStatementsForRefundRequestExpenseExpiredOrCancelled(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.REFUND_REQUEST_CANCELLED,
                          paymentBillingType: BillingType.BOLETO,
                          "refundRequestStatus[in]": [RefundRequestStatus.EXPIRED, RefundRequestStatus.CANCELLED],
                          transactionDate: transactionDate,
                          "refundRequestFinancialStatementDebitType[exists]": true,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT_REVERSAL]]
            List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementReversalInfoMapList = [
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT_REVERSAL]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementReversalInfoMapList, null)
        }, [logErrorMessage: "FinancialStatementRefundBankSlipService - Erro ao executar createFinancialStatementsForRefundRequestExpenseExpiredOrCancelled"])
    }

    private void createFinancialStatementsForRefundRequestFeeExpiredOrCancelled(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.REFUND_REQUEST_FEE_REVERSAL,
                          refundRequestPaymentBillingType: BillingType.BOLETO,
                          "refundRequestStatus[in]": [RefundRequestStatus.EXPIRED, RefundRequestStatus.CANCELLED],
                          transactionDate: transactionDate,
                          "refundRequestFeeFinancialStatementDebitType[exists]": true,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT_REVERSAL]]
            List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementReversalInfoMapList = [
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_FEE_TRANSITORY_DEBIT_REVERSAL],
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementReversalInfoMapList, null)
        }, [logErrorMessage: "FinancialStatementRefundBankSlipService - Erro ao executar createFinancialStatementsForRefundRequestFeeExpiredOrCancelled"])
    }

    private void createFinancialStatementsForRefundRequestFeeDiscount(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                          paymentBillingType: BillingType.BOLETO,
                          refundRequestStatus: RefundRequestStatus.PENDING,
                          transactionDate: transactionDate,
                          "paymentReversalPromotionalCodeStatement[notExists]": true,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_FEE_DISCOUNT_EXPENSE, FinancialStatementType.REFUND_REQUEST_FEE_DISCOUNT_DEBIT, FinancialStatementType.REFUND_REQUEST_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT],
                          "refundRequest[isNotNull]": true,
                          paymentStatus: PaymentStatus.RECEIVED]
            List<FinancialTransaction> refundRequestFeeDiscountTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!refundRequestFeeDiscountTransactionList) return

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

            List<Map> financialStatementInfoMapList = [
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_FEE_DISCOUNT_DEBIT],
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundRequestFeeDiscountTransactionList, financialStatementInfoMapList, bank)
        }, [logErrorMessage: "FinancialStatementRefundBankSlipService - Erro ao executar createFinancialStatementsForRefundRequestFeeDiscount"])
    }
}
