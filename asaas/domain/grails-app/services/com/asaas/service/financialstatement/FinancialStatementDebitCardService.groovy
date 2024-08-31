package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.debitcard.DebitCardAcquirer
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank

import grails.transaction.Transactional

@Transactional
class FinancialStatementDebitCardService {

    def financialStatementPaymentService
    def financialStatementService

    public void createFinancialStatementsForReceivedPayments(Date startDate, Date endDate) {
        DebitCardAcquirer debitCardAcquirer = DebitCardAcquirer.ADYEN
        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

        createPaymentCustomerRevenue(startDate, endDate, bank, debitCardAcquirer)
        createPaymentFeeRevenue(startDate, endDate, bank, debitCardAcquirer)
        createForPromotionalCodeCredit(startDate, endDate, bank, debitCardAcquirer)
    }

    private void createPaymentCustomerRevenue(Date startDate, Date endDate, Bank bank, DebitCardAcquirer debitCardAcquirer) {
        List<Long> receivedPaymentIdList = FinancialTransaction.query([
            column: "payment.id",
            transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
            paymentBillingTypeList: [BillingType.DEBIT_CARD],
            'transactionDate[ge]': startDate,
            'transactionDate[lt]': endDate,
            'debitCardAcquirer': debitCardAcquirer,
            'paymentFinancialStatementTypeList[notExists]': [FinancialStatementType.ADYEN_DEBIT_CARD_CUSTOMER_REVENUE, FinancialStatementType.CUSTOMER_REVENUE]
        ]).list()

        if (receivedPaymentIdList) financialStatementPaymentService.saveForConfirmedPayments(bank, receivedPaymentIdList)
    }

    private void createPaymentFeeRevenue(Date startDate, Date endDate, Bank bank, DebitCardAcquirer debitCardAcquirer) {
        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PAYMENT_FEE,
            paymentBillingType: BillingType.DEBIT_CARD,
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            debitCardAcquirer: debitCardAcquirer,
            "paymentFeeStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.ADYEN_DEBIT_CARD_FEE_REVENUE]
        ]).list(readOnly: true)
        if (!feeTransactionList) return

        Map<Date, List<FinancialTransaction>> feeTransactionListGroupedByDate = feeTransactionList.groupBy { it.transactionDate }
        for (Date transactionDate : feeTransactionListGroupedByDate.keySet()) {
            List<FinancialTransaction> debitCardFeeTransactions = feeTransactionListGroupedByDate[transactionDate]

            List<Map> financialStatementTypeInfoList = [
                [financialStatementType: FinancialStatementType.ADYEN_DEBIT_CARD_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.ADYEN_DEBIT_CARD_FEE_DEBIT]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", debitCardFeeTransactions, financialStatementTypeInfoList, bank)
        }
    }

    private void createForPromotionalCodeCredit(Date startDate, Date endDate, Bank bank, DebitCardAcquirer debitCardAcquirer) {
        List<FinancialTransaction> feeDiscountTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
            paymentBillingTypeList: [BillingType.DEBIT_CARD],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            debitCardAcquirer: debitCardAcquirer,
            "paymentFeeDiscountStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE, FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT],
            "payment[isNotNull]": true
        ]).list(readOnly: true)

        Map feeDiscountTransactionListGroupedByDate = feeDiscountTransactionList.groupBy { it.transactionDate }

        feeDiscountTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> feeDiscountTransactions ->
            financialStatementPaymentService.saveFeeDiscount(feeDiscountTransactions, bank)
        }
    }
}
