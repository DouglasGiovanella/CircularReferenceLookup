package com.asaas.service.financialstatement

import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.mobilephonerecharge.MobilePhoneRechargeStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementMobilePhoneRechargeService {

    def financialStatementService

    public void createFinancialStatements(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> createTransitoryForMobilePhoneRechargeScheduled")
            createTransitoryForMobilePhoneRechargeScheduled(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> Erro ao executar createTransitoryForMobilePhoneRechargeScheduled."])

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> createTransitoryForReversedMobilePhoneRecharge")
            createTransitoryForReversedMobilePhoneRecharge(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> Erro ao executar createTransitoryForReversedMobilePhoneRecharge."])

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> createTransitoryForConfirmedMobilePhoneRecharge")
            createTransitoryForConfirmedMobilePhoneRecharge(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> Erro ao executar createTransitoryForConfirmedMobilePhoneRecharge."])

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> createForMobilePhoneRechargeExpense")
            createForMobilePhoneRechargeExpense(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> Erro ao executar createForMobilePhoneRechargeExpense."])

        Utils.withNewTransactionAndRollbackOnError({
            AsaasLogger.info("FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> refundMobilePhoneRechargeExpense")
            refundMobilePhoneRechargeExpense(startDate, endDate)
        }, [logErrorMessage: "FinancialStatementMobilePhoneRechargeService.createFinancialStatements >>> Erro ao executar refundMobilePhoneRechargeExpense."])
    }

    private void createTransitoryForMobilePhoneRechargeScheduled(Date startDate, Date endDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.transactionDateDifferentFromMobilePhoneRechargeConfirmedDate([
            transactionType: FinancialTransactionType.MOBILE_PHONE_RECHARGE,
            "dateCreated[ge]": startDate,
            "dateCreated[lt]": endDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.MOBILE_PHONE_RECHARGE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
        ]).list()

        if (!financialTransactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.MOBILE_PHONE_RECHARGE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfo], null)
    }

    private void createTransitoryForReversedMobilePhoneRecharge(Date startDate, Date endDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.CANCEL_MOBILE_PHONE_RECHARGE,
            "dateCreated[ge]": startDate,
            "dateCreated[lt]": endDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.MOBILE_PHONE_RECHARGE_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
        ]).list()

        if (!financialTransactionList) return

        Map financialStatementInfo = [
            financialStatementType: FinancialStatementType.MOBILE_PHONE_RECHARGE_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, [financialStatementInfo], null)
    }

    private void createTransitoryForConfirmedMobilePhoneRecharge(Date startDate, Date endDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.MOBILE_PHONE_RECHARGE,
            mobilePhoneRechargeStatusList: [MobilePhoneRechargeStatus.CONFIRMED, MobilePhoneRechargeStatus.REFUNDED],
            "mobilePhoneRechargeConfirmedDate[ge]": startDate,
            "mobilePhoneRechargeConfirmedDate[lt]": endDate,
            "financialStatementTypeList[exists]": [FinancialStatementType.MOBILE_PHONE_RECHARGE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT],
            "financialStatementTypeList[notExists]": [FinancialStatementType.MOBILE_PHONE_RECHARGE_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
        ]).list()

        if (!financialTransactionList) return

        Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.financialTransactionMobilePhoneRecharge.mobilePhoneRecharge.confirmedDate }

        financialTransactionListGroupedByDate.each { Date confirmedDate, List<FinancialTransaction> financialTransactionListByDate ->
            FinancialStatement financialStatement = financialStatementService.save(
                FinancialStatementType.MOBILE_PHONE_RECHARGE_CONFIRMED_CUSTOMER_BALANCE_TRANSITORY_CREDIT,
                confirmedDate,
                null,
                financialTransactionListByDate.value.sum()
            )

            financialStatementService.saveItems(financialStatement, financialTransactionListByDate)
        }
    }

    private void createForMobilePhoneRechargeExpense(Date startDate, Date endDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.MOBILE_PHONE_RECHARGE,
            mobilePhoneRechargeStatusList: [MobilePhoneRechargeStatus.CONFIRMED, MobilePhoneRechargeStatus.REFUNDED],
            "mobilePhoneRechargeConfirmedDate[ge]": startDate,
            "mobilePhoneRechargeConfirmedDate[lt]": endDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.MOBILE_PHONE_RECHARGE_EXPENSE]
        ]).list()

        if (!financialTransactionList) return

        Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.financialTransactionMobilePhoneRecharge.mobilePhoneRecharge.confirmedDate }

        financialTransactionListGroupedByDate.each { Date confirmedDate, List<FinancialTransaction> financialTransactionListByDate ->
            FinancialStatement mobilePhoneRechargeExpense = financialStatementService.save(
                FinancialStatementType.MOBILE_PHONE_RECHARGE_EXPENSE,
                confirmedDate,
                null,
                financialTransactionListByDate.value.sum()
            )

            financialStatementService.saveItems(mobilePhoneRechargeExpense, financialTransactionListByDate)
        }
    }

    private void refundMobilePhoneRechargeExpense(Date startDate, Date endDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
            transactionType: FinancialTransactionType.REFUND_MOBILE_PHONE_RECHARGE,
            mobilePhoneRechargeStatus: MobilePhoneRechargeStatus.REFUNDED,
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.REFUND_MOBILE_PHONE_RECHARGE_EXPENSE]
        ]).list()

        if (!financialTransactionList) return

        Map<Date, List<FinancialTransaction>> financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.transactionDate }

        financialTransactionListGroupedByDate.each { Date transactionDate, List<FinancialTransaction> financialTransactionListByDate ->
            FinancialStatement mobilePhoneRechargeExpense = financialStatementService.save(
                FinancialStatementType.REFUND_MOBILE_PHONE_RECHARGE_EXPENSE,
                transactionDate,
                null,
                financialTransactionListByDate.value.sum()
            )

            financialStatementService.saveItems(mobilePhoneRechargeExpense, financialTransactionListByDate)
        }
    }
}
