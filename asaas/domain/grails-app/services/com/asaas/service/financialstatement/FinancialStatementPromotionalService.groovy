package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementPromotionalService {

    def financialStatementService

    public void createFinancialStatementsForPromotional(Date transactionDate) {
        AsaasLogger.info("FinancialStatementPromotionalService >> createForChargedFeePromotionalCode")
        createForChargedFeePromotionalCode(transactionDate)
        AsaasLogger.info("FinancialStatementPromotionalService >> createForChargedFeeRefundPromotionalCode")
        createForChargedFeeRefundPromotionalCode(transactionDate)
        AsaasLogger.info("FinancialStatementPromotionalService >> createFinancialStatementsFreePaymentDiscountFee")
        createFinancialStatementsFreePaymentDiscountFee(transactionDate)
    }

    private void createFinancialStatementsFreePaymentDiscountFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map queryParameters = [:]
            queryParameters.transactionType = FinancialTransactionType.FREE_PAYMENT_USE
            queryParameters.transactionDate = transactionDate
            queryParameters."financialStatementTypeList[notExists]" = [FinancialStatementType.FREE_PAYMENT_USE_CUSTOMER_BALANCE_CREDIT, FinancialStatementType.FREE_PAYMENT_USE_ASAAS_BALANCE_DEBIT]
            List<FinancialTransaction> transactionList = FinancialTransaction.query(queryParameters).list()
            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.FREE_PAYMENT_USE_CUSTOMER_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.FREE_PAYMENT_USE_ASAAS_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementPromotionalService - Erro ao executar createFinancialStatementsFreePaymentDiscountFee"])
    }

    private void createForChargedFeePromotionalCode(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> chargedFeePromotionalCodeTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_CREDIT,
                transactionDate: transactionDate,
                "chargedFee[isNotNull]": true,
                "chargedFeePromotionalCodeFinancialStatement[notExists]": true,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CHARGED_FEE_PROMOTIONAL_CODE_ASAAS_BALANCE_DEBIT, FinancialStatementType.CHARGED_FEE_PROMOTIONAL_CODE_CUSTOMER_BALANCE_CREDIT]
            ]).list()

            if (!chargedFeePromotionalCodeTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CHARGED_FEE_PROMOTIONAL_CODE_ASAAS_BALANCE_DEBIT],
                [financialStatementType: FinancialStatementType.CHARGED_FEE_PROMOTIONAL_CODE_CUSTOMER_BALANCE_CREDIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", chargedFeePromotionalCodeTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementPromotionalService - Erro ao executar createForChargedFeePromotionalCode"])
    }

    private void createForChargedFeeRefundPromotionalCode(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> chargedFeeRefundPromotionalCodeTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PROMOTIONAL_CODE_DEBIT,
                transactionDate: transactionDate,
                "chargedFee[isNotNull]": true,
                "chargedFeeRefundPromotionalCodeFinancialStatement[notExists]": true,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CHARGED_FEE_REFUND_PROMOTIONAL_CODE_ASAAS_BALANCE_CREDIT, FinancialStatementType.CHARGED_FEE_REFUND_PROMOTIONAL_CODE_CUSTOMER_BALANCE_DEBIT]
            ]).list()

            if (!chargedFeeRefundPromotionalCodeTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CHARGED_FEE_REFUND_PROMOTIONAL_CODE_ASAAS_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.CHARGED_FEE_REFUND_PROMOTIONAL_CODE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", chargedFeeRefundPromotionalCodeTransactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementPromotionalService - Erro ao executar createForChargedFeeRefundPromotionalCode"])
    }
}
