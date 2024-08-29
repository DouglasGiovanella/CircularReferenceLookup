package com.asaas.service.financialstatement

import com.asaas.asaascard.AsaasCardTransactionSettlementStatus
import com.asaas.asaascardtransaction.AsaasCardTransactionType
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementAsaasCardService {

    def financialStatementService

    public void createFinancialStatementsForAsaasCard(Date date) {
        AsaasLogger.info("FinancialStatementAsaasCardService >> createFinancialStatementsForAsaasDebitCardRequestFee")
        createFinancialStatementsForAsaasDebitCardRequestFee(date)
        AsaasLogger.info("FinancialStatementAsaasCardService >> createFinancialStatementsForAsaasPrepaidCardRequestFee")
        createFinancialStatementsForAsaasPrepaidCardRequestFee(date)
        AsaasLogger.info("FinancialStatementAsaasCardService >> createFinancialStatementsForAsaasDebitCardRequestFeeRefund")
        createFinancialStatementsForAsaasDebitCardRequestFeeRefund(date)
        AsaasLogger.info("FinancialStatementAsaasCardService >> createFinancialStatementsForAsaasPrepaidCardRequestFeeRefund")
        createFinancialStatementsForAsaasPrepaidCardRequestFeeRefund(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createForAsaasCardTransactionFeeRefund")
//        createForAsaasCardTransactionFeeRefund(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createForAsaasCardTransactionFeeRevenue")
//        createForAsaasCardTransactionFeeRevenue(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createTransitoryForAsaasCardRecharge")
//        createTransitoryForAsaasCardRecharge(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createTransitoryForAsaasCardTransaction")
//        createTransitoryForAsaasCardTransaction(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createTransitoryForAsaasCardTransactionPaid")
//        createTransitoryForAsaasCardTransactionPaid(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createTransitoryForAsaasCardTransactionRefund")
//        createTransitoryForAsaasCardTransactionRefund(date)
//        AsaasLogger.info("FinancialStatementAsaasCardService >> createTransitoryForAsaasCardTransactionRefundCancellation")
//        createTransitoryForAsaasCardTransactionRefundCancellation(date)
        AsaasLogger.info("FinancialStatementAsaasCardService >> createForAsaasCardBillPayment")
        createForAsaasCardBillPayment(date)
        AsaasLogger.info("FinancialStatementAsaasCardService >> createForAsaasCardCashback")
        createForAsaasCardCashback(date)
    }

    public FinancialStatement createTransitoryForAsaasCardPrepaidTransactionPurchase(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_PURCHASE_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForAsaasCardPrepaidTransactionWithdrawal(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionPurchaseRefund(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionWithdrawalRefund(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionPurchaseRefundCancellation(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionWithdrawalRefundCancellation(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionPurchasePaid(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_PURCHASE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT,
            statementDate,
            null,
            value)
    }

    public FinancialStatement createTransitoryForPrepaidAsaasCardTransactionWithdrawalPaid(Date statementDate, BigDecimal value) {
        return financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT,
            statementDate,
            null,
            value)
    }

    public List<FinancialStatement> createForAsaasCardBalanceRefund(Date statementDate, BigDecimal value) {
        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        List<FinancialStatement> financialStatementList = []

        FinancialStatement customerDebit = financialStatementService.save(FinancialStatementType.ASAAS_CARD_BALANCE_REFUND_CUSTOMER_DEBIT, statementDate, bank, value)
        if (customerDebit.hasErrors()) throw new BusinessException(DomainUtils.getValidationMessages(customerDebit.getErrors()).first())
        financialStatementList.add(customerDebit)

        FinancialStatement customerCredit = financialStatementService.save(FinancialStatementType.ASAAS_CARD_BALANCE_REFUND_CUSTOMER_CREDIT, statementDate, bank, value)
        if (customerCredit.hasErrors()) throw new BusinessException(DomainUtils.getValidationMessages(customerCredit.getErrors()).first())
        financialStatementList.add(customerCredit)

        return financialStatementList
    }

    public List<FinancialStatement> createForPrepaidAsaasCardTransactionWithdrawalFeeRevenue(Date statementDate, BigDecimal value) {
        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        List<FinancialStatement> financialStatementList = []

        FinancialStatement asaasRevenue = financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REVENUE, statementDate, bank, value)
        if (asaasRevenue.hasErrors()) throw new BusinessException(Utils.getMessageProperty(asaasRevenue.errors.allErrors.first()))
        financialStatementList.add(asaasRevenue)

        FinancialStatement customerDebit = financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_CUSTOMER_BALANCE_DEBIT, statementDate, bank, value)
        if (customerDebit.hasErrors()) throw new BusinessException(Utils.getMessageProperty(customerDebit.errors.allErrors.first()))
        financialStatementList.add(customerDebit)

        return financialStatementList
    }

    public List<FinancialStatement> createForPrepaidAsaasCardTransactionWithdrawalFeeRevenueRefund(Date statementDate, BigDecimal value) {
        List<FinancialStatement> financialStatementList = []

        FinancialStatement customerCredit = financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_CUSTOMER_BALANCE_CREDIT, statementDate, null, value)
        if (customerCredit.hasErrors()) throw new BusinessException(Utils.getMessageProperty(customerCredit.errors.allErrors.first()))
        financialStatementList.add(customerCredit)

        FinancialStatement asaasDebit = financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_ASAAS_BALANCE_DEBIT, statementDate, null, value)
        if (asaasDebit.hasErrors()) throw new BusinessException(Utils.getMessageProperty(customerCredit.errors.allErrors.first()))
        financialStatementList.add(asaasDebit)

        return financialStatementList
    }

    public List<FinancialStatement> createTransitoryForCreditAsaasCardTransactionPurchase(Date statementDate, BigDecimal value) {
        List<FinancialStatement> financialStatementList = []

        FinancialStatement transitoryCredit = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_BILLS_TO_PAY_TRANSITORY_CREDIT, statementDate, null, value)
        financialStatementList.add(transitoryCredit)

        FinancialStatement transitoryDebit = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_BILLS_TO_RECEIVE_TRANSITORY_DEBIT, statementDate, null, value)
        financialStatementList.add(transitoryDebit)

        return financialStatementList
    }

    public FinancialStatement createForCreditAsaasCardTransactionPurchaseRefund(Date statementDate, BigDecimal value) {
        Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
        FinancialStatement asaasRefund = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_PURCHASE_REFUND_ASAAS_BALANCE_CREDIT, statementDate, bank, value)

        return asaasRefund
    }

    public FinancialStatement createTransitoryForCreditAsaasCardPositiveBillsCredit(Date statementDate, BigDecimal value) {
        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_POSITIVE_BILL_TRANSITORY_CREDIT, statementDate, null, value)
        return financialStatement
    }

    public FinancialStatement createTransitoryForCreditAsaasCardPositiveBillsDebit(Date statementDate, BigDecimal value) {
        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_POSITIVE_BILL_TRANSITORY_DEBIT, statementDate, null, value)
        return financialStatement
    }

    public FinancialStatement createForCreditAsaasCardTransactionPurchaseSettlement(Date statementDate, BigDecimal value) {
        Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
        FinancialStatement asaasDebit = financialStatementService.save(FinancialStatementType.ASAAS_CREDIT_CARD_PURCHASE_SETTLEMENT_ASAAS_BALANCE_DEBIT, statementDate, bank, value)

        return asaasDebit
    }

    private void createTransitoryForAsaasCardRecharge(Date date) {
        Map filter = ["transactionType": FinancialTransactionType.ASAAS_CARD_RECHARGE,
                      "transactionDate": date,
                      "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_RECHARGE_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.PREPAID_ASAAS_CARD_BALANCE]]

        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query(filter).list(readOnly: true)
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_CARD_RECHARGE_CUSTOMER_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.PREPAID_ASAAS_CARD_BALANCE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, Bank.findByCode(SupportedBank.BRADESCO.code()))
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createTransitoryForAsaasCardRecharge"])
    }

    private void createFinancialStatementsForAsaasDebitCardRequestFee(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.ASAAS_DEBIT_CARD_REQUEST_FEE,
                transactionDate: date,
                'financialStatementTypeList[notExists]': [FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REVENUE, FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createFinancialStatementsForAsaasDebitCardRequestFee"])
    }

    private void createFinancialStatementsForAsaasPrepaidCardRequestFee(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.ASAAS_PREPAID_CARD_REQUEST_FEE,
                transactionDate: date,
                'financialStatementTypeList[notExists]': [FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REVENUE, FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createFinancialStatementsForAsaasPrepaidCardRequestFee"])
    }

    private void createFinancialStatementsForAsaasDebitCardRequestFeeRefund(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                chargedFeeType: ChargedFeeType.ASAAS_DEBIT_CARD_REQUEST,
                transactionDate: date,
                'financialStatementTypeList[notExists]': [FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REFUND_ASAAS_BALANCE_DEBIT, FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REFUND_ASAAS_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.ASAAS_DEBIT_CARD_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createFinancialStatementsForAsaasDebitCardRequestFeeRefund"])
    }

    private void createFinancialStatementsForAsaasPrepaidCardRequestFeeRefund(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                chargedFeeType: ChargedFeeType.ASAAS_PREPAID_CARD_REQUEST,
                transactionDate: date,
                'financialStatementTypeList[notExists]': [FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REFUND_ASAAS_BALANCE_DEBIT, FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REFUND_ASAAS_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.ASAAS_PREPAID_CARD_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createFinancialStatementsForAsaasPrepaidCardRequestFeeRefund"])
    }

    private void createForAsaasCardTransactionFeeRefund(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> refundWithdrawalFeeFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.REFUND_WITHDRAWAL,
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_TRANSACTION_FEE_REFUND],
                transactionDate: date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_CUSTOMER_BALANCE_CREDIT, FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_ASAAS_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!refundWithdrawalFeeFinancialTransactionList) return

            List<Map> financialStatementInfo = [
                [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_CUSTOMER_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REFUND_ASAAS_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundWithdrawalFeeFinancialTransactionList, financialStatementInfo, bank)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createForAsaasCardTransactionFeeRefund"])
    }

    private void createForAsaasCardTransactionFeeRevenue(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> withdrawalFeeFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.WITHDRAWAL,
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION_FEE,
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REVENUE, FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!withdrawalFeeFinancialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", withdrawalFeeFinancialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createForAsaasCardTransactionFeeRevenue"])
    }

    private void createTransitoryForAsaasCardTransaction(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> purchaseFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.PURCHASE,
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION,
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_PURCHASE_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list(readOnly: true)

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_PURCHASE_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", purchaseFinancialTransactionList, [financialStatementInfo], null)

            List<FinancialTransaction> withdrawalFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.WITHDRAWAL,
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION,
                transactionDate: date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list(readOnly: true)

            financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", withdrawalFinancialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createTransitoryForAsaasCardTransaction"])
    }

    private void createTransitoryForAsaasCardTransactionPaid(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> paidPurchaseFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.PURCHASE,
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION,
                "asaasCardTransactionSettlementEstimatedSettlementDate[ge]": date,
                "asaasCardTransactionSettlementEstimatedSettlementDate[lt]": CustomDateUtils.sumDays(date, 1),
                'asaasCardTransactionSettlementStatus': AsaasCardTransactionSettlementStatus.DONE,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_PURCHASE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list(readOnly: true)

            Map paidPurchaseFinancialTransactionListGroupByDateMap = paidPurchaseFinancialTransactionList.groupBy { FinancialTransaction financialTransaction -> financialTransaction.financialTransactionAsaasCardTransaction.asaasCardTransaction.asaasCardTransactionSettlement.estimatedSettlementDate }

            paidPurchaseFinancialTransactionListGroupByDateMap.each { Date estimatedSettlementDate, List<FinancialTransaction> paidPurchaseFinancialTransactionListGroupByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.ASAAS_CARD_PURCHASE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT, estimatedSettlementDate, null, paidPurchaseFinancialTransactionListGroupByDate.value.sum())
                financialStatementService.saveItems(financialStatement, paidPurchaseFinancialTransactionListGroupByDate)
            }

            List<FinancialTransaction> paidWithdrawalFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.WITHDRAWAL,
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION,
                "asaasCardTransactionSettlementEstimatedSettlementDate[ge]": date,
                "asaasCardTransactionSettlementEstimatedSettlementDate[lt]": CustomDateUtils.sumDays(date, 1),
                "asaasCardTransactionSettlementStatus": AsaasCardTransactionSettlementStatus.DONE,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list(readOnly: true)

            Map paidWithdrawalFinancialTransactionListGroupByDateMap = paidWithdrawalFinancialTransactionList.groupBy { FinancialTransaction financialTransaction -> financialTransaction.financialTransactionAsaasCardTransaction.asaasCardTransaction.asaasCardTransactionSettlement.estimatedSettlementDate }

            paidWithdrawalFinancialTransactionListGroupByDateMap.each { Date estimatedSettlementDate, List<FinancialTransaction> paidWithdrawalFinancialTransactionListGroupByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.ASAAS_CARD_WITHDRAWAL_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT, estimatedSettlementDate, null, paidWithdrawalFinancialTransactionListGroupByDate.value.sum())
                financialStatementService.saveItems(financialStatement, paidWithdrawalFinancialTransactionListGroupByDate)
            }
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createTransitoryForAsaasCardTransactionPaid"])
    }

    private void createTransitoryForAsaasCardTransactionRefund(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> refundPurchaseFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.REFUND_PURCHASE,
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND, FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND],
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list(readOnly: true)

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundPurchaseFinancialTransactionList, [financialStatementInfo], null)

            List<FinancialTransaction> refundWithdrawalFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.REFUND_WITHDRAWAL,
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND, FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND],
                transactionDate: date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list(readOnly: true)

            financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CUSTOMER_BALANCE_TRANSITORY_CREDIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundWithdrawalFinancialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createTransitoryForAsaasCardTransactionRefund"])
    }

    private void createTransitoryForAsaasCardTransactionRefundCancellation(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> refundCancellationPurchaseFinancialTransactionList = FinancialTransaction.query([
                asaasCardTransactionType: AsaasCardTransactionType.REFUND_CANCELLED_PURCHASE,
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND_CANCELLATION, FinancialTransactionType.ASAAS_CARD_TRANSACTION_PARTIAL_REFUND_CANCELLATION],
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list(readOnly: true)

            Map financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_PURCHASE_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundCancellationPurchaseFinancialTransactionList, [financialStatementInfo], null)

            List<FinancialTransaction> refundCancellationWithdrawalFinancialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.ASAAS_CARD_TRANSACTION_REFUND_CANCELLATION,
                asaasCardTransactionType: AsaasCardTransactionType.REFUND_CANCELLED_WITHDRAWAL,
                transactionDate: date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list(readOnly: true)

            financialStatementInfo = [financialStatementType: FinancialStatementType.ASAAS_CARD_WITHDRAWAL_REFUND_CANCELLED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", refundCancellationWithdrawalFinancialTransactionList, [financialStatementInfo], null)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createTransitoryForAsaasCardTransactionRefundCancellation"])
    }

    private void createForAsaasCardBillPayment(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> asaasCardBillPaymentFinancialTransactionList = FinancialTransaction.query([
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_BILL_PAYMENT],
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_BILL_PAYMENT_CUSTOMER_BALANCE_DEBIT, FinancialStatementType.ASAAS_CARD_BILL_PAYMENT_BALANCE_CREDIT]
            ]).list(readOnly: true)

            if (!asaasCardBillPaymentFinancialTransactionList) return

            List<Map> financialStatementInfo = [
                [financialStatementType: FinancialStatementType.ASAAS_CARD_BILL_PAYMENT_CUSTOMER_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.ASAAS_CARD_BILL_PAYMENT_BALANCE_CREDIT]
            ]

            Bank bank = Bank.findByCode(SupportedBank.ASAAS.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", asaasCardBillPaymentFinancialTransactionList, financialStatementInfo, bank)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createForAsaasCardBillPayment"])
    }

    private void createForAsaasCardCashback(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> asaasCardCashbackPaymentFinancialTransactionList = FinancialTransaction.query([
                transactionTypeList: [FinancialTransactionType.ASAAS_CARD_CASHBACK],
                "transactionDate": date,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_CARD_CASHBACK_CUSTOMER_BALANCE_CREDIT]
            ]).list(readOnly: true)

            if (!asaasCardCashbackPaymentFinancialTransactionList) return

            Map financialStatementInfo = [
                financialStatementType: FinancialStatementType.ASAAS_CARD_CASHBACK_CUSTOMER_BALANCE_CREDIT
            ]

            Bank bank = Bank.query([code: SupportedBank.BRADESCO.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", asaasCardCashbackPaymentFinancialTransactionList, [financialStatementInfo], bank)
        }, [logErrorMessage: "FinancialStatementAsaasCardService - Erro ao executar createForAsaasCardCashback"])
    }
}
