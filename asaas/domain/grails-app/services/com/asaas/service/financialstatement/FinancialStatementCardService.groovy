package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardAcquirerOperationEnum
import com.asaas.creditcardacquireroperation.CreditCardAcquirerOperationStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.creditcard.CreditCardAcquirerOperation
import com.asaas.domain.creditcard.PaymentCreditCardDepositFileItem
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementAcquirerUtils
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementCardService {

    def financialStatementPaymentService
    def financialStatementService

    public void createFinancialStatementsForAcquirerDepositOperation(Date date, CreditCardAcquirer creditCardAcquirer) {
        Bank bank = FinancialStatementAcquirerUtils.getAcquirerBank(creditCardAcquirer)

        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerDeposit(date, creditCardAcquirer, bank)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerForDepositOperation - Erro ao executar createForAcquirerDeposit",
            onError: { Exception exception -> throw exception }
        ])
    }

    public void createFinancialStatementsForAcquirerFeeDifferenceOperation(Date date, CreditCardAcquirer creditCardAcquirer) {
        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerFeeDifference(date, creditCardAcquirer)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerFeeDifferenceOperation - Erro ao executar createForRefundedReversed",
            onError: { Exception exception -> throw exception }
        ])
    }

    public void createFinancialStatementsForAcquirerRefundAndChargebackOperation(Date date) {
        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate(date, acquirer)
        }
    }

    public void createFinancialStatementsForReceivedPayments(Date transactionDate) {
        for (CreditCardAcquirer creditCardAcquirer in CreditCardAcquirer.listToFilter()) {
            Bank bank = FinancialStatementAcquirerUtils.getAcquirerBank(creditCardAcquirer)

            Utils.withNewTransactionAndRollbackOnError({
                createPaymentCustomerRevenue(transactionDate, bank, creditCardAcquirer)
            }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForReceivedPayments >> Erro ao executar createPaymentCustomerRevenue"])
            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                createPaymentFeeRevenue(transactionDate, bank, creditCardAcquirer)
            }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForReceivedPayments >> Erro ao executar createPaymentFeeRevenue"])
            Utils.flushAndClearSession()

            Utils.withNewTransactionAndRollbackOnError({
                createForPromotionalCodeCredit(transactionDate, bank, creditCardAcquirer)
            }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForReceivedPayments >> Erro ao executar createForPromotionalCodeCredit"])
            Utils.flushAndClearSession()
        }
    }

    public void createFinancialStatementsForChargeback(Date startDate, Date endDate) {
        for (CreditCardAcquirer creditCardAcquirer in CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                createForChargeback(startDate, endDate, creditCardAcquirer)
                createForChargebackReversal(startDate, endDate, creditCardAcquirer)
            }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForChargeback >> Erro ao executar createFinancialStatementsForChargeback"])
        }
    }

    public void createFinancialStatementsForRefund(Date startDate, Date endDate) {
        for (CreditCardAcquirer creditCardAcquirer in CreditCardAcquirer.listToFilter()) {
            Utils.withNewTransactionAndRollbackOnError({
                Bank bank = FinancialStatementAcquirerUtils.getAcquirerBank(creditCardAcquirer)
                createForPaymentReversal(startDate, endDate, bank, creditCardAcquirer)
                createForFeeReversal(startDate, endDate, bank, creditCardAcquirer)
            }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForRefund >> Erro ao executar createFinancialStatementsForRefund"])
        }
    }

    public void createForBatchAnticipation(Date date, CreditCardAcquirer creditCardAcquirer) {
        if (!creditCardAcquirer.isAdyen()) return

        Utils.withNewTransactionAndRollbackOnError({
            FinancialStatementType creditStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_BATCH_ANTICIPATION_CREDIT
            FinancialStatementType debitStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_BATCH_ANTICIPATION_SETTLEMENT_DEBIT

            List<CreditCardAcquirerOperation> acquirerOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.BULK_ADVANCEMENT], [creditStatementType, debitStatementType])
            if (!acquirerOperationList) return

            Map<Date, List<CreditCardAcquirerOperation>> acquirerOperationListGroupedByDateMap = acquirerOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }
            for (Date operationDate : acquirerOperationListGroupedByDateMap.keySet()) {
                List<CreditCardAcquirerOperation> operationList = acquirerOperationListGroupedByDateMap[operationDate]

                BigDecimal totalValue = operationList.sum { it.value }

                FinancialStatement creditFinancialStatement = financialStatementService.save(creditStatementType, operationDate, null, totalValue)
                financialStatementService.saveItems(creditFinancialStatement, operationList)

                Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
                FinancialStatement debitFinancialStatement = financialStatementService.save(debitStatementType, operationDate, bank, totalValue)
                financialStatementService.saveItems(debitFinancialStatement, operationList)
            }
        }, [logErrorMessage: "FinancialStatementCardService.createForBatchAnticipation >> Erro ao gerar lançamentos contábeis para operações de antecipação em lote da Adyen",
            onError: { Exception exception -> throw exception }
        ])
    }

    public void createFinancialStatementsForBalanceTransferOperation(Date date, CreditCardAcquirer acquirer) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialStatementType> financialStatementTypeCreditList = FinancialStatementAcquirerUtils.getAcquirerBalanceTransferCreditFinancialStatementType(acquirer)
            List<FinancialStatementType> financialStatementTypeDebitList = FinancialStatementAcquirerUtils.getAcquirerBalanceTransferDebitFinancialStatementType(acquirer)
            List<CreditCardAcquirerOperation> creditCardAcquirerOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, acquirer, [CreditCardAcquirerOperationEnum.BALANCE_TRANSFER], financialStatementTypeCreditList + financialStatementTypeDebitList)
            if (!creditCardAcquirerOperationList) return

            Map<Date, List<CreditCardAcquirerOperation>> creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }
            for (Date operationDate : creditCardAcquirerOperationListGroupByDateMap.keySet()) {
                List<CreditCardAcquirerOperation> operationList = creditCardAcquirerOperationListGroupByDateMap[operationDate]

                List<CreditCardAcquirerOperation> balanceTransferCreditItems = operationList.findAll { it.value > 0 }
                List<CreditCardAcquirerOperation> balanceTransferDebitItems = operationList.findAll { it.value < 0 }

                if (balanceTransferCreditItems) createBalanceTransferFinancialStatements(balanceTransferCreditItems, financialStatementTypeCreditList)
                if (balanceTransferDebitItems) createBalanceTransferFinancialStatements(balanceTransferDebitItems, financialStatementTypeDebitList)
            }

        }, [logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForBalanceTransferOperation >> Erro ao executar createFinancialStatementsForBalanceTransferOperation"])
    }

    private void createBalanceTransferFinancialStatements(List<CreditCardAcquirerOperation> creditCardOperationBalanceTransferList, List<FinancialStatementType> financialStatementTypeList) {
        BigDecimal totalValue = creditCardOperationBalanceTransferList.sum { it.value }
        Date statementDate = creditCardOperationBalanceTransferList.first().operationDate

        for (FinancialStatementType financialStatementType : financialStatementTypeList) {
            FinancialStatement financialStatement = financialStatementService.save(financialStatementType, statementDate, null, totalValue)
            financialStatementService.saveItems(financialStatement, creditCardOperationBalanceTransferList)
        }
    }

    private void createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate(Date date, CreditCardAcquirer creditCardAcquirer) {
        Bank bank = FinancialStatementAcquirerUtils.getAcquirerBank(creditCardAcquirer)

        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerChargebackOperations(date, creditCardAcquirer, bank)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForAcquirerChargeback",
            onError: { Exception exception -> throw exception }
        ])

        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerRefund(date, creditCardAcquirer, bank)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForAcquirerRefund",
            onError: { Exception exception -> throw exception }
        ])

        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerChargebackRefund(date, creditCardAcquirer, bank)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForAcquirerInvoiceDeduction",
            onError: { Exception exception -> throw exception }
        ])

        Utils.withNewTransactionAndRollbackOnError({
            createForAcquirerInvoiceDeduction(date, creditCardAcquirer, bank)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForAcquirerInvoiceDeduction",
            onError: { Exception exception -> throw exception }
        ])

        Utils.withNewTransactionAndRollbackOnError({
            createForSecondChargeback(date, creditCardAcquirer)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForSecondChargeback",
            onError: { Exception exception -> throw exception }
        ])

        Utils.withNewTransactionAndRollbackOnError({
            createForRefundedReversed(date, creditCardAcquirer)
        }, [
            logErrorMessage: "FinancialStatementCardService.createFinancialStatementsForAcquirerRefundAndChargebackOperationForDate - Erro ao executar createForRefundedReversed",
            onError: { Exception exception -> throw exception }
        ])

        /***
         * Estorno parcial comentado pois aguarda repasse do código de transação no Sinqia
         * createForAcquirerPartialRefund(date, creditCardAcquirer, bank)
         */
    }

    private void createPaymentCustomerRevenue(Date date, Bank bank, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType customerRevenueStatementType = FinancialStatementAcquirerUtils.getAcquirerCustomerRevenueFinancialStatementType(creditCardAcquirer)

        List<Long> receivedPaymentIdList = FinancialTransaction.query([
            column: "payment.id",
            transactionType: FinancialTransactionType.PAYMENT_RECEIVED,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            transactionDate: date,
            creditCardAcquirer: creditCardAcquirer,
            "paymentFinancialStatementTypeList[notExists]": [customerRevenueStatementType, FinancialStatementType.CUSTOMER_REVENUE]
        ]).list()

        if (!receivedPaymentIdList) return

        financialStatementPaymentService.saveForConfirmedPayments(bank, receivedPaymentIdList)
    }

    private void createPaymentFeeRevenue(Date date, Bank bank, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType feeDebitStatementType = FinancialStatementAcquirerUtils.getAcquirerFeeDebitFinancialStatementType(creditCardAcquirer)
        FinancialStatementType feeRevenueStatementType = FinancialStatementAcquirerUtils.getAcquirerFeeRevenueFinancialStatementType(creditCardAcquirer)
        FinancialStatementType receivedFeeFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardReceivedFeeTransitoryDebitFinancialStatementType(creditCardAcquirer)

        List<FinancialTransaction> feeTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PAYMENT_FEE,
            paymentBillingType: BillingType.MUNDIPAGG_CIELO,
            transactionDate: date,
            creditCardAcquirer: creditCardAcquirer,
            "paymentFeeStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [feeDebitStatementType]
        ]).list(readOnly: true)

        if (!feeTransactionList) return

        List<Map> financialStatementTypeWithBankInfoList = [
            [financialStatementType: feeDebitStatementType],
            [financialStatementType: feeRevenueStatementType]
        ]
        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", feeTransactionList, financialStatementTypeWithBankInfoList, bank)

        List<Map> financialStatementTypeWithoutBankInfoList = [
            [financialStatementType: receivedFeeFinancialStatementType]
        ]
        financialStatementService.groupFinancialTransactionsAndSaveInBatch("transactionDate", feeTransactionList, financialStatementTypeWithoutBankInfoList, null)
    }

    private void createForPromotionalCodeCredit(Date date, Bank bank, CreditCardAcquirer creditCardAcquirer) {
        List<FinancialTransaction> feeDiscountTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            transactionDate: date,
            creditCardAcquirer: creditCardAcquirer,
            "paymentFeeDiscountStatement[notExists]": true,
            "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_FEE_DISCOUNT_EXPENSE, FinancialStatementType.PAYMENT_FEE_DISCOUNT_CUSTOMER_BALANCE_CREDIT],
            "payment[isNotNull]": true
        ]).list(readOnly: true)

        financialStatementPaymentService.saveFeeDiscount(feeDiscountTransactionList, bank)
    }

    private void createForChargeback(Date startDate, Date endDate, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType chargebackStatementType = FinancialStatementAcquirerUtils.getAcquirerCustomerChargebackFinancialStatementType(creditCardAcquirer)

        List<FinancialTransaction> chargebackTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.CHARGEBACK,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "creditCardAcquirer": creditCardAcquirer,
            "financialStatementTypeList[notExists]": [chargebackStatementType]
        ]).list()

        Map chargebackTransactionListGroupedByDate = chargebackTransactionList.groupBy { it.transactionDate }

        chargebackTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> chargebackTransactionsList ->
            BigDecimal chargebackValue = chargebackTransactionsList.sum { it.value }
            String description = "${chargebackStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement chargebackfinancialStatement = financialStatementService.save(chargebackStatementType, transactionDate, null, chargebackValue, description)
            financialStatementService.saveItems(chargebackfinancialStatement, chargebackTransactionsList)
        }
    }

    private void createForChargebackReversal(Date startDate, Date endDate, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType chargebackReversalStatementType = FinancialStatementAcquirerUtils.getAcquirerCustomerChargebackReversalFinancialStatementType(creditCardAcquirer)

        List<FinancialTransaction> chargebackReversalTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.CHARGEBACK_REVERSAL,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "creditCardAcquirer": creditCardAcquirer,
            "financialStatementTypeList[notExists]": [chargebackReversalStatementType]
        ]).list()

        Map chargebackReversalTransactionListGroupedByDate = chargebackReversalTransactionList.groupBy { it.transactionDate }

        chargebackReversalTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> chargebackReversalTransactionsList ->
            BigDecimal reversedValue = chargebackReversalTransactionsList.sum { it.value }
            String description = "${chargebackReversalStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement chargebackReversalfinancialStatement = financialStatementService.save(chargebackReversalStatementType, transactionDate, null, reversedValue, description)
            financialStatementService.saveItems(chargebackReversalfinancialStatement, chargebackReversalTransactionsList)
        }
    }

    private void createForPaymentReversal(Date startDate, Date endDate, Bank bank, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType customerRefundDebitType = FinancialStatementAcquirerUtils.getAcquirerCustomerRefundFinancialStatementType(creditCardAcquirer)
        FinancialStatementType acquirerRefundCreditType = FinancialStatementAcquirerUtils.getAcquirerRefundCreditFinancialStatementType(creditCardAcquirer)

        List<FinancialTransaction> paymentReversalTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PAYMENT_REVERSAL,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "creditCardAcquirer": creditCardAcquirer,
            "financialStatementTypeList[notExists]": [acquirerRefundCreditType, customerRefundDebitType]
        ]).list()

        Map paymentReversalTransactionListGroupedByDate = paymentReversalTransactionList.groupBy { it.transactionDate }

        paymentReversalTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> refundTransactionsList ->
            BigDecimal reversedValue = refundTransactionsList.sum { it.value }
            String customerDescription = "${customerRefundDebitType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement customerRefundFinancialStatement = financialStatementService.save(customerRefundDebitType, transactionDate, bank, reversedValue, customerDescription)
            financialStatementService.saveItems(customerRefundFinancialStatement, refundTransactionsList)

            String acquirerDescription = "${acquirerRefundCreditType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement acquirerRefundFinancialStatement = financialStatementService.save(acquirerRefundCreditType, transactionDate, bank, reversedValue, acquirerDescription)
            financialStatementService.saveItems(acquirerRefundFinancialStatement, refundTransactionsList)
        }
    }

    private void createForFeeReversal(Date startDate, Date endDate, Bank bank, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType customerRefundDebitType = FinancialStatementAcquirerUtils.getAcquirerCustomerFeeReversalFinancialStatementType(creditCardAcquirer)
        FinancialStatementType acquirerRefundCreditType = FinancialStatementAcquirerUtils.getAcquirerFeeReversalFinancialStatementType(creditCardAcquirer)

        List<FinancialTransaction> paymentFeeReversalTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.PAYMENT_FEE_REVERSAL,
            paymentBillingTypeList: [BillingType.MUNDIPAGG_CIELO],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "creditCardAcquirer": creditCardAcquirer,
            "financialStatementTypeList[notExists]": [acquirerRefundCreditType, customerRefundDebitType]
        ]).list()

        Map chargebackReversalTransactionListGroupedByDate = paymentFeeReversalTransactionList.groupBy { it.transactionDate }

        chargebackReversalTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> refundTransactionsList ->
            BigDecimal reversedValue = refundTransactionsList.sum { it.value }
            String customerDescription = "${customerRefundDebitType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement customerRefundFinancialStatement = financialStatementService.save(customerRefundDebitType, transactionDate, bank, reversedValue, customerDescription)
            financialStatementService.saveItems(customerRefundFinancialStatement, refundTransactionsList)

            String acquirerDescription = "${acquirerRefundCreditType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement acquirerRefundFinancialStatement = financialStatementService.save(acquirerRefundCreditType, transactionDate, bank, reversedValue, acquirerDescription)
            financialStatementService.saveItems(acquirerRefundFinancialStatement, refundTransactionsList)
        }
    }

    private void createFinancialStatementsForFeeExpense(List<CreditCardAcquirerOperation> creditCardAcquirerOperationList, Bank bank, Date operationDate, CreditCardAcquirer creditCardAcquirer) {
        if (!creditCardAcquirerOperationList) return

        BigDecimal creditCardFee = creditCardAcquirerOperationList.sum { it.getCreditCardFee() }

        FinancialStatementType feeExpenseFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerFeeExpenseFinancialStatementType(creditCardAcquirer)
        String description = "${feeExpenseFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
        FinancialStatement feeExpenseFinancialStatement = financialStatementService.save(feeExpenseFinancialStatementType, operationDate, bank, creditCardFee, description)

        FinancialStatementType transactionFeeCreditFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeTransitoryCreditFinancialStatementType(creditCardAcquirer)
        FinancialStatement transactionFeeCreditFinancialStatement = financialStatementService.save(transactionFeeCreditFinancialStatementType, operationDate, null, creditCardFee)

        financialStatementService.saveItems(feeExpenseFinancialStatement, creditCardAcquirerOperationList)
        financialStatementService.saveItems(transactionFeeCreditFinancialStatement, creditCardAcquirerOperationList)
    }

    private void createFinancialStatementsForIdentifiedDeposit(List<CreditCardAcquirerOperation> creditCardAcquirerOperationList, Bank bank, Date operationDate, CreditCardAcquirer creditCardAcquirer) {
        List<CreditCardAcquirerOperation> creditCardAcquirerOperationListWithPayment = creditCardAcquirerOperationList.findAll { it.payment }
        if (!creditCardAcquirerOperationListWithPayment) return
        BigDecimal identifiedSettlementValue = creditCardAcquirerOperationListWithPayment.sum { it.value }

        FinancialStatementType settlementFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditFinancialStatementType(creditCardAcquirer)
        String description = "${settlementFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
        FinancialStatement settlementCreditFinancialStatement = financialStatementService.save(settlementFinancialStatementType, operationDate, bank, identifiedSettlementValue, description)

        FinancialStatementType toReceiveDebitFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardToReceiveTransitoryDebitFinancialStatementType(creditCardAcquirer)
        FinancialStatement toReceiveDebitFinancialStatement = financialStatementService.save(toReceiveDebitFinancialStatementType, operationDate, null, identifiedSettlementValue)

        financialStatementService.saveItems(settlementCreditFinancialStatement, creditCardAcquirerOperationListWithPayment)
        financialStatementService.saveItems(toReceiveDebitFinancialStatement, creditCardAcquirerOperationListWithPayment)
    }

    private void createFinancialStatementsForNotIdentifiedDeposit(List<CreditCardAcquirerOperation> creditCardAcquirerOperationList, Bank bank, Date operationDate, FinancialStatementType financialStatementType, CreditCardAcquirer creditCardAcquirer) {
        List<CreditCardAcquirerOperation> creditCardAcquirerOperationListWithoutPayment = creditCardAcquirerOperationList.findAll { !it.payment }
        if (!creditCardAcquirerOperationListWithoutPayment) return
        BigDecimal notIdentifiedSettlementValue = creditCardAcquirerOperationListWithoutPayment.sum { it.value }
        String description = "${financialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
        FinancialStatement notIdentifiedfinancialStatement = financialStatementService.save(financialStatementType, operationDate, bank, notIdentifiedSettlementValue, description)
        financialStatementService.saveItems(notIdentifiedfinancialStatement, creditCardAcquirerOperationListWithoutPayment)

        if (creditCardAcquirer.isRede()) return

        FinancialStatementType notIdentifiedDebitStatementType = FinancialStatementAcquirerUtils.getAcquirerReceivedCreditCardNotIdentifiedDebitFinancialStatementType(creditCardAcquirer)

        FinancialStatement notIdentifiedDebitStatement = financialStatementService.save(notIdentifiedDebitStatementType, operationDate, null, notIdentifiedSettlementValue)
        financialStatementService.saveItems(notIdentifiedDebitStatement, creditCardAcquirerOperationListWithoutPayment)
    }

    private void createForAcquirerDeposit(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        FinancialStatementType feeExpenseFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerFeeExpenseFinancialStatementType(creditCardAcquirer)
        FinancialStatementType transactionFeeCreditFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeTransitoryCreditFinancialStatementType(creditCardAcquirer)
        FinancialStatementType settlementFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditFinancialStatementType(creditCardAcquirer)
        FinancialStatementType toReceiveDebitFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardToReceiveTransitoryDebitFinancialStatementType(creditCardAcquirer)
        FinancialStatementType acquirerNotIdentifiedCreditFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerNotIdentifiedCreditFinancialStatementType(creditCardAcquirer)

        List<FinancialStatementType> financialStatementTypeList = [feeExpenseFinancialStatementType, transactionFeeCreditFinancialStatementType, settlementFinancialStatementType, toReceiveDebitFinancialStatementType, acquirerNotIdentifiedCreditFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerDepositOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.DEPOSIT], financialStatementTypeList)
        if (!creditCardAcquirerDepositOperationList) return

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerDepositOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        creditCardAcquirerOperationListGroupByDateMap.each { Date operationDate, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate ->
            createFinancialStatementsForFeeExpense(creditCardAcquirerOperationListGroupByDate, bank, operationDate, creditCardAcquirer)
            createFinancialStatementsForIdentifiedDeposit(creditCardAcquirerOperationListGroupByDate, bank, operationDate, creditCardAcquirer)
            createFinancialStatementsForNotIdentifiedDeposit(creditCardAcquirerOperationListGroupByDate, bank, operationDate, acquirerNotIdentifiedCreditFinancialStatementType, creditCardAcquirer)
        }
    }

    private void createForAcquirerChargebackOperations(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        FinancialStatementType acquirerChargebackFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerChargebackFinancialStatementType(creditCardAcquirer)
        FinancialStatementType acquirerPaidChargebackFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerPaidCreditCardChargebackTransitoryCreditFinancialStatementType(creditCardAcquirer)

        List<FinancialStatementType> financialStatementTypeList = [acquirerChargebackFinancialStatementType, acquirerPaidChargebackFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerChargebackOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.CHARGEBACK], financialStatementTypeList)
        if (!creditCardAcquirerChargebackOperationList) return

        createForAcquirerChargeback(creditCardAcquirer, bank, creditCardAcquirerChargebackOperationList)
        createForPaidChargeback(creditCardAcquirer, creditCardAcquirerChargebackOperationList)
        if (creditCardAcquirer.isAdyen()) createForChargebackFee(creditCardAcquirer, creditCardAcquirerChargebackOperationList)
    }

    private void createForAcquirerChargeback(CreditCardAcquirer creditCardAcquirer, Bank bank, List<CreditCardAcquirerOperation> creditCardAcquirerChargebackOperationList) {
        FinancialStatementType acquirerChargebackFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerChargebackFinancialStatementType(creditCardAcquirer)

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerChargebackOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        creditCardAcquirerOperationListGroupByDateMap.each { Date operationDate, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate ->
            BigDecimal chargebackValue = creditCardAcquirerOperationListGroupByDate.sum { it.value }
            String description = "${acquirerChargebackFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement chargebackFinancialStatement = financialStatementService.save(acquirerChargebackFinancialStatementType, operationDate, bank, chargebackValue, description)
            financialStatementService.saveItems(chargebackFinancialStatement, creditCardAcquirerOperationListGroupByDate)
        }
    }

    private void createForChargebackFee(CreditCardAcquirer acquirer, List<CreditCardAcquirerOperation> creditCardAcquirerChargebackOperationList) {
        Map<Date, List<CreditCardAcquirerOperation>> creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerChargebackOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        for (Date operationDate : creditCardAcquirerOperationListGroupByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> operationList = creditCardAcquirerOperationListGroupByDateMap[operationDate]

            FinancialStatementType statementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardChargebackFeeTransitoryDebitFinancialStatementType(acquirer)
            BigDecimal feeTotalValue = operationList.sum { it.getCreditCardFee() }
            Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())

            FinancialStatement financialStatement = financialStatementService.save(statementType, operationDate, bank, feeTotalValue)
            financialStatementService.saveItems(financialStatement, operationList)
        }
    }

    private void createForPaidChargeback(CreditCardAcquirer acquirer, List<CreditCardAcquirerOperation> creditCardAcquirerOperationList) {
        FinancialStatementType statementType = FinancialStatementAcquirerUtils.getAcquirerPaidCreditCardChargebackTransitoryCreditFinancialStatementType(acquirer)

        Map<Date, List<CreditCardAcquirerOperation>> operationListGroupedByDateMap = creditCardAcquirerOperationList.groupBy { it.operationDate }
        for (Date operationDate : operationListGroupedByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> operationList = operationListGroupedByDateMap[operationDate]

            BigDecimal totalValue = operationList.sum { it.value }
            FinancialStatement financialStatement = financialStatementService.save(statementType, operationDate, null, totalValue)

            financialStatementService.saveItems(financialStatement, operationList)
        }
    }

    private void createForSecondChargeback(Date date, CreditCardAcquirer creditCardAcquirer) {
        if (!creditCardAcquirer.isAdyen()) return

        FinancialStatementType secondChargebackTransitoryCreditFinanancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_SECOND_CHARGEBACK_TRANSITORY_CREDIT
        FinancialStatementType secondChargebackTransitoryDebitFinanancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_SECOND_CHARGEBACK_TRANSITORY_DEBIT
        FinancialStatementType secondChargebackFeeTransitoryFinanancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_SECOND_CHARGEBACK_FEE_TRANSITORY_DEBIT

        List<FinancialStatementType> financialStatementTypeList = [secondChargebackTransitoryCreditFinanancialStatementType, secondChargebackTransitoryDebitFinanancialStatementType, secondChargebackFeeTransitoryFinanancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.SECOND_CHARGEBACK], financialStatementTypeList)
        if (!creditCardAcquirerOperationList) return

        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())

        Map<Date, List<CreditCardAcquirerOperation>> operationListGroupedByDateMap = creditCardAcquirerOperationList.groupBy { it.operationDate }
        for (Date operationDate : operationListGroupedByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> operationList = operationListGroupedByDateMap[operationDate]

            BigDecimal totalValue = operationList.sum { it.value }
            BigDecimal feeTotalValue = operationList.sum { it.getCreditCardFee() }

            List<Map> financialStatementInfoMapList = [
                [statementType: secondChargebackTransitoryCreditFinanancialStatementType,
                 totalValue: totalValue],
                [statementType: secondChargebackTransitoryDebitFinanancialStatementType,
                 totalValue: totalValue],
                [statementType: secondChargebackFeeTransitoryFinanancialStatementType,
                 totalValue: feeTotalValue]
            ]

            for (Map financialStatementInfoMap : financialStatementInfoMapList) {
                FinancialStatement financialStatement = financialStatementService.save(financialStatementInfoMap.statementType, operationDate, bank, financialStatementInfoMap.totalValue)
                financialStatementService.saveItems(financialStatement, operationList)
            }
        }
    }

    private void createForAcquirerChargebackRefund(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        if (!creditCardAcquirer.isAdyen()) return

        FinancialStatementType chargebackRefundCreditFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_CHARGEBACK_REFUND_CREDIT
        FinancialStatementType chargebackRefundDebitFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_CHARGEBACK_REFUND_TRANSITORY_DEBIT

        List<FinancialStatementType> financialStatementTypeList = [chargebackRefundCreditFinancialStatementType, chargebackRefundDebitFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerChargebackRefundOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.CHARGEBACK_REFUND], financialStatementTypeList)
        if (!creditCardAcquirerChargebackRefundOperationList) return

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerChargebackRefundOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        creditCardAcquirerOperationListGroupByDateMap.each { Date operationDate, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate ->
            BigDecimal chargebackValue = creditCardAcquirerOperationListGroupByDate.sum { it.value }

            String description = "${chargebackRefundCreditFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement chargebackFinancialStatement = financialStatementService.save(chargebackRefundCreditFinancialStatementType, operationDate, bank, chargebackValue, description)

            FinancialStatement chargebackRefundDebitFinancialStatement = financialStatementService.save(chargebackRefundDebitFinancialStatementType, operationDate, null, chargebackValue)

            financialStatementService.saveItems(chargebackFinancialStatement, creditCardAcquirerOperationListGroupByDate)
            financialStatementService.saveItems(chargebackRefundDebitFinancialStatement, creditCardAcquirerOperationListGroupByDate)
        }
    }

    private void createForAcquirerRefund(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        FinancialStatementType acquirerRefundFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerRefundFinancialStatementType(creditCardAcquirer)
        FinancialStatementType acquirerChargebackRefundFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerChargebackRefundSettlementTransitoryFinancialStatementType(creditCardAcquirer)

        List<FinancialStatementType> financialStatementTypeList = [acquirerRefundFinancialStatementType, acquirerChargebackRefundFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerRefundOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.REFUND], financialStatementTypeList)
        if (!creditCardAcquirerRefundOperationList) return

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerRefundOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        for (Date operationDate : creditCardAcquirerOperationListGroupByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate = creditCardAcquirerOperationListGroupByDateMap[operationDate]
            BigDecimal refundValue = creditCardAcquirerOperationListGroupByDate.sum { it.value }

            createForAcquirerRefundForDate(creditCardAcquirer, operationDate, bank, refundValue, creditCardAcquirerOperationListGroupByDate)
            createForAcquirerChargebackRefundSettlementForDate(creditCardAcquirer, operationDate, bank, refundValue, creditCardAcquirerOperationListGroupByDate)
        }
    }

    private void createForAcquirerRefundForDate(CreditCardAcquirer creditCardAcquirer, Date operationDate, Bank bank, BigDecimal refundValue, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate) {
        FinancialStatementType acquirerRefundFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerRefundFinancialStatementType(creditCardAcquirer)
        String description = "${acquirerRefundFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
        FinancialStatement refundFinancialStatement = financialStatementService.save(acquirerRefundFinancialStatementType, operationDate, bank, refundValue, description)
        financialStatementService.saveItems(refundFinancialStatement, creditCardAcquirerOperationListGroupByDate)
    }

    private void createForAcquirerChargebackRefundSettlementForDate(CreditCardAcquirer creditCardAcquirer, Date operationDate, Bank bank, BigDecimal refundValue, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate) {
        FinancialStatementType acquirerRefundFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerChargebackRefundSettlementTransitoryFinancialStatementType(creditCardAcquirer)
        String description = "${acquirerRefundFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
        FinancialStatement refundFinancialStatement = financialStatementService.save(acquirerRefundFinancialStatementType, operationDate, bank, refundValue, description)
        financialStatementService.saveItems(refundFinancialStatement, creditCardAcquirerOperationListGroupByDate)
    }

    private void createForAcquirerInvoiceDeduction(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        if (!creditCardAcquirer.isAdyen()) return

        FinancialStatementType creditCardInvoiceDeductionCreditFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_INVOICE_DEDUCTION_CREDIT
        FinancialStatementType creditCardInvoiceDeductionDebitFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_INVOICE_DEDUCTION_DEBIT

        List<FinancialStatementType> financialStatementTypeList = [creditCardInvoiceDeductionCreditFinancialStatementType, creditCardInvoiceDeductionDebitFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerInvoiceDeductionOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.INVOICE_DEDUCTION], financialStatementTypeList)

        List<List<CreditCardAcquirerOperation>> debitAndCreditGroupedOperationList = creditCardAcquirerInvoiceDeductionOperationList.split { it.netValue < 0 }

        for (List<CreditCardAcquirerOperation> creditCardAcquirerInvoiceDeductionOperationByTypeList : debitAndCreditGroupedOperationList) {
            if (!creditCardAcquirerInvoiceDeductionOperationByTypeList) continue

            CreditCardAcquirerOperation invoiceDeductionOperation = creditCardAcquirerInvoiceDeductionOperationByTypeList.first()

            FinancialStatementType acquirerInvoiceDeductionFinancialStatementType
            if (invoiceDeductionOperation.netValue < 0) {
                acquirerInvoiceDeductionFinancialStatementType = creditCardInvoiceDeductionDebitFinancialStatementType
            } else {
                acquirerInvoiceDeductionFinancialStatementType = creditCardInvoiceDeductionCreditFinancialStatementType
            }

            Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerInvoiceDeductionOperationByTypeList.groupBy { it.operationDate }

            for (Date operationDate : creditCardAcquirerOperationListGroupByDateMap.keySet()) {
                List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate = creditCardAcquirerOperationListGroupByDateMap[operationDate]
                BigDecimal value = creditCardAcquirerOperationListGroupByDate.sum { it.netValue }
                FinancialStatement invoiceDeductionFinancialStatement = financialStatementService.save(acquirerInvoiceDeductionFinancialStatementType, operationDate, bank, value)
                financialStatementService.saveItems(invoiceDeductionFinancialStatement, creditCardAcquirerOperationListGroupByDate)
            }
        }
    }

    private void createForAcquirerPartialRefund(Date date, CreditCardAcquirer creditCardAcquirer, Bank bank) {
        FinancialStatementType acquirerPartialRefundFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerPartialRefundFinancialStatementType(creditCardAcquirer)

        List<FinancialStatementType> financialStatementTypeList = [acquirerPartialRefundFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerPartialRefundOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.PARTIAL_REFUND], financialStatementTypeList)
        if (!creditCardAcquirerPartialRefundOperationList) return

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerPartialRefundOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        creditCardAcquirerOperationListGroupByDateMap.each { Date operationDate, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate ->
            BigDecimal refundValue = creditCardAcquirerOperationListGroupByDate.sum { it.value }
            String description = "${acquirerPartialRefundFinancialStatementType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement refundFinancialStatement = financialStatementService.save(acquirerPartialRefundFinancialStatementType, operationDate, bank, refundValue, null, description)
            financialStatementService.saveItems(refundFinancialStatement, creditCardAcquirerOperationListGroupByDate)
        }
    }

    private void createForAcquirerFeeDifference(Date date, CreditCardAcquirer creditCardAcquirer) {
        FinancialStatementType debitFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeComplementTransitoryDebitFinancialStatementType(creditCardAcquirer)
        FinancialStatementType creditFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeReversalTransitoryCreditFinancialStatementType(creditCardAcquirer)

        List<FinancialStatementType> financialStatementTypeList = [debitFinancialStatementType, creditFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, CreditCardAcquirerOperationEnum.feeDifferenceOperationList(), financialStatementTypeList)
        if (!creditCardAcquirerOperationList) return

        Map<Date, List<CreditCardAcquirerOperation>> creditCardAcquirerOperationListGroupedByDateMap = creditCardAcquirerOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }
        for (Date operationDate : creditCardAcquirerOperationListGroupedByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate = creditCardAcquirerOperationListGroupedByDateMap[operationDate]

            processAcquirerFeeDifference(creditCardAcquirerOperationListGroupByDate, creditCardAcquirer, operationDate)
        }
    }

    private void createForRefundedReversed(Date date, CreditCardAcquirer creditCardAcquirer) {
        if (!creditCardAcquirer.isAdyen()) return

        FinancialStatementType refundReversalTransitoryCreditFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_REFUND_REVERSAL_TRANSITORY_CREDIT
        FinancialStatementType refundReversalTransitoryDebitFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_REFUND_REVERSAL_TRANSITORY_DEBIT
        List<FinancialStatementType> financialStatementTypeList = [refundReversalTransitoryCreditFinancialStatementType, refundReversalTransitoryDebitFinancialStatementType]

        List<CreditCardAcquirerOperation> creditCardAcquirerOperationList = getCreditCardAcquirerOperationsForFinancialStatement(date, creditCardAcquirer, [CreditCardAcquirerOperationEnum.REFUNDED_REVERSED], financialStatementTypeList)
        if (!creditCardAcquirerOperationList) return

        Map creditCardAcquirerOperationListGroupByDateMap = creditCardAcquirerOperationList.groupBy { CreditCardAcquirerOperation creditCardAcquirerOperation -> creditCardAcquirerOperation.operationDate }

        for (Date operationDate : creditCardAcquirerOperationListGroupByDateMap.keySet()) {
            List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate = creditCardAcquirerOperationListGroupByDateMap[operationDate]
            BigDecimal totalValue = creditCardAcquirerOperationListGroupByDate.sum { it.value }

            createForAcquirerRefundedReversedForDate(operationDate, totalValue, creditCardAcquirerOperationListGroupByDate)
        }
    }

    private void createForAcquirerRefundedReversedForDate(Date operationDate, BigDecimal value, List<CreditCardAcquirerOperation> creditCardAcquirerOperationListGroupByDate) {
        FinancialStatementType creditFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_REFUND_REVERSAL_TRANSITORY_CREDIT
        FinancialStatement creditFinancialStatement = financialStatementService.save(creditFinancialStatementType, operationDate, null, value)
        financialStatementService.saveItems(creditFinancialStatement, creditCardAcquirerOperationListGroupByDate)

        FinancialStatementType debitFinancialStatementType = FinancialStatementType.ADYEN_CREDIT_CARD_REFUND_REVERSAL_TRANSITORY_DEBIT
        FinancialStatement debitFinancialStatement = financialStatementService.save(debitFinancialStatementType, operationDate, null, value)
        financialStatementService.saveItems(debitFinancialStatement, creditCardAcquirerOperationListGroupByDate)
    }

    private void processAcquirerFeeDifference(List<CreditCardAcquirerOperation> creditCardAcquirerOperationList, CreditCardAcquirer acquirer, Date operationDate) {
        List<Map> acquirerFeeComplementInfoMapList = []
        List<Map> acquirerFeeReversalInfoMapList = []

        for (CreditCardAcquirerOperation creditCardAcquirerOperation : creditCardAcquirerOperationList) {
            BigDecimal transactionFeeAmount = PaymentCreditCardDepositFileItem.query([paymentId: creditCardAcquirerOperation.paymentId, column: "transactionFeeAmount"]).get() ?: 0
            BigDecimal acquirerFeeDifference = creditCardAcquirerOperation.getCreditCardFee() - transactionFeeAmount

            if (acquirerFeeDifference == 0) continue

            if (acquirerFeeDifference > 0) {
                acquirerFeeComplementInfoMapList.add([
                    creditCardAcquirerOperation: creditCardAcquirerOperation,
                    value: acquirerFeeDifference
                ])
            } else {
                acquirerFeeReversalInfoMapList.add([
                    creditCardAcquirerOperation: creditCardAcquirerOperation,
                    value: acquirerFeeDifference
                ])
            }
        }

        if (acquirerFeeComplementInfoMapList) {
            FinancialStatementType debitFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeComplementTransitoryDebitFinancialStatementType(acquirer)
            saveAcquirerFeeDifferenceFinancialStatement(acquirerFeeComplementInfoMapList, debitFinancialStatementType, operationDate)
        }

        if (acquirerFeeReversalInfoMapList) {
            FinancialStatementType creditFinancialStatementType = FinancialStatementAcquirerUtils.getAcquirerCreditCardTransactionFeeReversalTransitoryCreditFinancialStatementType(acquirer)
            saveAcquirerFeeDifferenceFinancialStatement(acquirerFeeReversalInfoMapList, creditFinancialStatementType, operationDate)
        }
    }

    private void saveAcquirerFeeDifferenceFinancialStatement(List<Map> creditCardAcquirerOperationInfoList, FinancialStatementType financialStatementType, Date statementDate) {
        BigDecimal totalValue = creditCardAcquirerOperationInfoList.sum { it.value }
        FinancialStatement financialStatement = financialStatementService.save(financialStatementType, statementDate, null, totalValue)

        List<CreditCardAcquirerOperation> creditCardAcquirerOperationList = creditCardAcquirerOperationInfoList.collect { it.creditCardAcquirerOperation }
        financialStatementService.saveItems(financialStatement, creditCardAcquirerOperationList)
    }

    private List<CreditCardAcquirerOperation> getCreditCardAcquirerOperationsForFinancialStatement(Date date, CreditCardAcquirer creditCardAcquirer, List<CreditCardAcquirerOperationEnum> creditCardAcquirerOperationEnumList, List<FinancialStatementType> financialStatementTypeList) {
        List<FinancialStatementType> notExistsTypeList = [FinancialStatementType.CREDIT_CARD_FEE_EXPENSE, FinancialStatementType.NOT_IDENTIFIED_CREDIT_CARD_DEPOSIT_CREDIT]
        notExistsTypeList += financialStatementTypeList

        Map search = [
            "dateCreated[ge]": date,
            "dateCreated[lt]": CustomDateUtils.sumDays(date, 1),
            "status[in]": [CreditCardAcquirerOperationStatus.PROCESSED, CreditCardAcquirerOperationStatus.ERROR, CreditCardAcquirerOperationStatus.IGNORED],
            operationList: creditCardAcquirerOperationEnumList,
            "creditCardAcquirerOperationBatchAcquirer[in]": [creditCardAcquirer],
            "financialStatementTypeList[notExists]":  notExistsTypeList
        ]

        return CreditCardAcquirerOperation.query(search).list()
    }
}
