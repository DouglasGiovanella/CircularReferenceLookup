package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.payment.PaymentStatus
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementRefundService {

    def financialStatementService

    public void createForBankSlipAndDebitCardPaymentReversal(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
                          "refundRequestPaymentBillingTypeList[in]": [BillingType.BOLETO, BillingType.DEBIT_CARD],
                          transactionDate: transactionDate,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT]]
            List<FinancialTransaction> paymentReversalTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!paymentReversalTransactionList) return

            List<Map> financialStatementInfoMapList = [
                [financialStatementType: FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", paymentReversalTransactionList, financialStatementInfoMapList, null)
        }, [logErrorMessage: "FinancialStatementRefundService >> Erro ao executar createForBankSlipAndDebitCardPaymentReversal"])
    }

    public void createForBankSlipAndDebitCardPaymentReversalPaid(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map search = [transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
                          "refundRequestPaymentBillingTypeList[in]": [BillingType.BOLETO, BillingType.DEBIT_CARD],
                          refundRequestPaymentStatus: PaymentStatus.REFUNDED,
                          refundRequestStatus: RefundRequestStatus.PAID,
                          refundPaymentDate: transactionDate,
                          "refundRequestFinancialStatementDebitType[exists]": true,
                          "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT_REVERSAL, FinancialStatementType.REFUND_REQUEST_EXPENSE]
            ]

            List<FinancialTransaction> refundRequestFeeTransactionList = FinancialTransaction.query(search).list(readOnly: true)
            if (!refundRequestFeeTransactionList) return

            saveFinancialStatementAndItems(refundRequestFeeTransactionList, FinancialStatementType.REFUND_REQUEST_TRANSITORY_DEBIT_REVERSAL, transactionDate, null)

            Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
            saveFinancialStatementAndItems(refundRequestFeeTransactionList, FinancialStatementType.REFUND_REQUEST_EXPENSE, transactionDate, bank)
        }, [logErrorMessage: "FinancialStatementRefundService >> Erro ao executar createForBankSlipAndDebitCardPaymentReversalPaid"])
    }

    public void saveFinancialStatementAndItems(List<FinancialTransaction> financialTransactionList, FinancialStatementType financialStatementType, Date statementDate, Bank bank) {
        BigDecimal totalValue = financialTransactionList.value.sum()
        FinancialStatement financialStatement = financialStatementService.save(financialStatementType, statementDate, bank, totalValue)

        financialStatementService.saveItems(financialStatement, financialTransactionList)
    }
}
