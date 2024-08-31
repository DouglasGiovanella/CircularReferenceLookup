package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementNotificationService {

    def financialStatementService

    public void createFinancialStatementsForNotification(Date transactionDate) {
        AsaasLogger.info("FinancialStatementNotificationService >> createPaymentInstantTextMessageFeeStatementList")
        createPaymentInstantTextMessageFeeStatementList(transactionDate)
        Utils.flushAndClearSession()

        AsaasLogger.info("FinancialStatementNotificationService >> createPaymentSmsNotificationFeeStatements")
        createPaymentSmsNotificationFeeStatements(transactionDate)
        Utils.flushAndClearSession()

        AsaasLogger.info("FinancialStatementNotificationService >> createPhoneCallNotificationFeeStatements")
        createPhoneCallNotificationFeeStatements(transactionDate)
        Utils.flushAndClearSession()

        AsaasLogger.info("FinancialStatementNotificationService >> createForPaymentMessagingNotificationFee")
        createForPaymentMessagingNotificationFee(transactionDate)
    }

    private void createForPaymentMessagingNotificationFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_MESSAGING_NOTIFICATION_FEE,
                transactionDate: CustomDateUtils.formatDate(transactionDate),
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_MESSAGING_NOTIFICATION_FEE_REVENUE, FinancialStatementType.PAYMENT_MESSAGING_NOTIFICATION_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_MESSAGING_NOTIFICATION_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_MESSAGING_NOTIFICATION_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementNotificationService - Erro ao executar createForPaymentMessagingNotificationFee"])
    }

    private void createPaymentInstantTextMessageFeeStatementList(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> instantTextMessageFeeTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.INSTANT_TEXT_MESSAGE_FEE,
                transactionDate: CustomDateUtils.formatDate(transactionDate),
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_INSTANT_TEXT_MESSAGE_FEE_REVENUE, FinancialStatementType.PAYMENT_INSTANT_TEXT_MESSAGE_FEE_EXPENSE]
            ]).list(readOnly: true)

            if (!instantTextMessageFeeTransactionList) return

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_INSTANT_TEXT_MESSAGE_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_INSTANT_TEXT_MESSAGE_FEE_EXPENSE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", instantTextMessageFeeTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementNotificationService - Erro ao executar createPaymentInstantTextMessageFeeStatementList"])
    }

    private void createPaymentSmsNotificationFeeStatements(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_SMS_NOTIFICATION_FEE,
                transactionDate: CustomDateUtils.formatDate(transactionDate),
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_SMS_NOTIFICATION_FEE_EXPENSE, FinancialStatementType.PAYMENT_SMS_NOTIFICATION_FEE_REVENUE]
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_SMS_NOTIFICATION_FEE_EXPENSE],
                [financialStatementType: FinancialStatementType.PAYMENT_SMS_NOTIFICATION_FEE_REVENUE]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementNotificationService - Erro ao executar createPaymentSmsNotificationFeeStatements"])
    }

    private void createPhoneCallNotificationFeeStatements(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PHONE_CALL_NOTIFICATION_FEE,
                transactionDate: CustomDateUtils.formatDate(transactionDate),
                "financialStatementTypeList[notExists]": [FinancialStatementType.PHONE_CALL_NOTIFICATION_FEE_REVENUE, FinancialStatementType.PHONE_CALL_NOTIFICATION_FEE_EXPENSE]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PHONE_CALL_NOTIFICATION_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PHONE_CALL_NOTIFICATION_FEE_EXPENSE]
            ]

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementNotificationService - Erro ao executar createPhoneCallNotificationFeeStatements"])
    }
}
