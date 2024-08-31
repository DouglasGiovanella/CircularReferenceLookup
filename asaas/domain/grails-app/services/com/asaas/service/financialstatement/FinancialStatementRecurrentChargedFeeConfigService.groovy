package com.asaas.service.financialstatement

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementRecurrentChargedFeeConfigService {

    def financialStatementService

    public void createFinancialStatementsForRecurrentChargedFeeConfig(Date transactionDate) {
        AsaasLogger.info("FinancialStatementRecurrentChargedFeeConfigService >> createFinancialStatementsForContractedCustomerPlanFee")
        createFinancialStatementsForContractedCustomerPlanFee(transactionDate)

        AsaasLogger.info("FinancialStatementRecurrentChargedFeeConfigService >> createFinancialStatementsForContractedCustomerPlanFeeRefund")
        createFinancialStatementsForContractedCustomerPlanFeeRefund(transactionDate)

        AsaasLogger.info("FinancialStatementRecurrentChargedFeeConfigService >> createForAccountInactivityFee")
        createForAccountInactivityFee(transactionDate)

        AsaasLogger.info("FinancialStatementRecurrentChargedFeeConfigService >> createForAccountInactivityFeeRefund")
        createForAccountInactivityFeeRefund(transactionDate)
    }

    private void createFinancialStatementsForContractedCustomerPlanFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CONTRACTED_CUSTOMER_PLAN_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CONTRACTED_CUSTOMER_PLAN_FEE_REVENUE, FinancialStatementType.CONTRACTED_CUSTOMER_PLAN_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CONTRACTED_CUSTOMER_PLAN_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.CONTRACTED_CUSTOMER_PLAN_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementRecurrentChargedFeeConfigService - Erro ao executar createFinancialStatementsForContractedCustomerPlanFee"])
    }

    private void createFinancialStatementsForContractedCustomerPlanFeeRefund(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                chargedFeeType: ChargedFeeType.CONTRACTED_CUSTOMER_PLAN,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_CONTRACTED_CUSTOMER_PLAN_FEE_REVENUE, FinancialStatementType.REFUND_CONTRACTED_CUSTOMER_PLAN_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.REFUND_CONTRACTED_CUSTOMER_PLAN_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.REFUND_CONTRACTED_CUSTOMER_PLAN_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementRecurrentChargedFeeConfigService - Erro ao executar createFinancialStatementsForContractedCustomerPlanFeeRefund"])
    }

    private void createForAccountInactivityFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.ACCOUNT_INACTIVITY_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.ACCOUNT_INACTIVITY_FEE_REVENUE, FinancialStatementType.ACCOUNT_INACTIVITY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.ACCOUNT_INACTIVITY_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.ACCOUNT_INACTIVITY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementRecurrentChargedFeeConfigService - Erro ao executar createForAccountInactivityFee"])
    }

    private void createForAccountInactivityFeeRefund(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                chargedFeeType: ChargedFeeType.ACCOUNT_INACTIVITY,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_ACCOUNT_INACTIVITY_FEE_REVENUE, FinancialStatementType.REFUND_ACCOUNT_INACTIVITY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)

            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.REFUND_ACCOUNT_INACTIVITY_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.REFUND_ACCOUNT_INACTIVITY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementRecurrentChargedFeeConfigService - Erro ao executar createForAccountInactivityFeeRefund"])
    }
}
