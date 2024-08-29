package com.asaas.service.financialstatement

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.PaymentDunningAccountability
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliation
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningAccountabilityStatus
import com.asaas.payment.PaymentDunningAccountabilityType
import com.asaas.payment.PaymentDunningBatchPartner
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementDunningService {

    def financialStatementService
    def grailsApplication

    public void createFinancialStatementsForDunning(Date date) {
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningReceivedByPartnerFee")
        createForPaymentDunningReceivedByPartnerFee(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningAccountabilityReceivedByPartner")
        createForPaymentDunningAccountabilityReceivedByPartner(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningReceivedDirectlyFee")
        createForPaymentDunningReceivedDirectlyFee(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningAccountabilityReceivedDirectly")
        createForPaymentDunningAccountabilityReceivedDirectly(date)
        AsaasLogger.info("FinancialStatementDunningService >> createFinancialStatementsForPartialPayments")
        createFinancialStatementsForPartialPayments(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningRequestFee")
        createForPaymentDunningRequestFee(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningRequestFeeRefund")
        createForPaymentDunningRequestFeeRefund(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForCreditBureauDunningCustomerFeeExpense")
        createForCreditBureauDunningCustomerFeeExpense(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForCreditBureauDunningAsaasFeeExpense")
        createForCreditBureauDunningAsaasFeeExpense(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForPaymentDunningCancellationFee")
        createForPaymentDunningCancellationFee(date)
        AsaasLogger.info("FinancialStatementDunningService >> createForCreditBureauReportFee")
        createForCreditBureauReportFee(date)
    }

    private void createFinancialStatementsForPartialPayments(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PARTIAL_PAYMENT,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_RECEIVED_REVENUE],
                transactionDate: transactionDate
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            Map financialTransactionListGroupedByDate = financialTransactionList.groupBy { it.partialPayment.accountability.paymentDate }

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

            financialTransactionListGroupedByDate.each { Date paymentDate, List<FinancialTransaction> financialTransactionListByDate ->
                FinancialStatement customerRevenue = financialStatementService.save(FinancialStatementType.PAYMENT_DUNNING_RECEIVED_REVENUE, paymentDate, bank, financialTransactionListByDate.value.sum())
                financialStatementService.saveItems(customerRevenue, financialTransactionListByDate)
            }
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createFinancialStatementsForPartialPayments"])
    }

    private void createForCreditBureauDunningAsaasFeeExpense(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            Customer asaasCustomer = Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)

            List<CreditBureauDunningConciliation> creditBureauDunningConciliationList = CreditBureauDunningConciliation.conciliationConfirmed([customer: asaasCustomer, "analysisDate[ge]": date, "analysisDate[lt]": CustomDateUtils.sumDays(date, 1), "financialStatementType[notExists]": FinancialStatementType.CREDIT_BUREAU_DUNNING_ASAAS_FEE_EXPENSE]).list()

            if (!creditBureauDunningConciliationList) return

            Map creditBureauDunningConciliationGroupedByDate = creditBureauDunningConciliationList.groupBy { it.analysisDate.clone().clearTime() }

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            creditBureauDunningConciliationGroupedByDate.each { Date analysisDate, List<CreditBureauDunningConciliation> creditBureauDunningConciliationListByDate ->
                BigDecimal partnerChargedFeeValueSum = creditBureauDunningConciliationListByDate*.getSumPartnerChargedFeeValue().sum()

                FinancialStatement creditBureauDunningAsaasExpense = financialStatementService.save(FinancialStatementType.CREDIT_BUREAU_DUNNING_ASAAS_FEE_EXPENSE, analysisDate, bank, partnerChargedFeeValueSum)
                financialStatementService.saveItems(creditBureauDunningAsaasExpense, creditBureauDunningConciliationListByDate)
            }
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForCreditBureauDunningAsaasFeeExpense"])
    }

    private void createForCreditBureauDunningCustomerFeeExpense(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<CreditBureauDunningConciliation> creditBureauDunningConciliationList = CreditBureauDunningConciliation.conciliationConfirmed([excludeAsaasCustomer: true, "analysisDate[ge]": date, "analysisDate[lt]": CustomDateUtils.sumDays(date, 1), "financialStatementType[notExists]": FinancialStatementType.CREDIT_BUREAU_DUNNING_CUSTOMER_FEE_EXPENSE]).list()

            if (!creditBureauDunningConciliationList) return

            Map creditBureauDunningConciliationGroupedByDate = creditBureauDunningConciliationList.groupBy { it.analysisDate.clone().clearTime() }

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            creditBureauDunningConciliationGroupedByDate.each { Date analysisDate, List<CreditBureauDunningConciliation> creditBureauDunningConciliationListByDate ->
                BigDecimal partnerChargedFeeValueSum = creditBureauDunningConciliationListByDate*.getSumPartnerChargedFeeValue().sum()

                FinancialStatement creditBureauDunningCustomerExpense = financialStatementService.save(FinancialStatementType.CREDIT_BUREAU_DUNNING_CUSTOMER_FEE_EXPENSE, analysisDate, bank, partnerChargedFeeValueSum)
                financialStatementService.saveItems(creditBureauDunningCustomerExpense, creditBureauDunningConciliationListByDate)
            }
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForCreditBureauDunningCustomerFeeExpense"])
    }

    private void createForCreditBureauReportFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CREDIT_BUREAU_REPORT,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CREDIT_BUREAU_REPORT_FEE_REVENUE]
            ]).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.CREDIT_BUREAU_REPORT_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.CREDIT_BUREAU_REPORT_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForCreditBureauReportFee"])
    }

    private void createForPaymentDunningAccountabilityReceivedByPartner(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<PaymentDunningAccountability> accountabilityFineList = PaymentDunningAccountability.query([paymentDunningBatchPartner: PaymentDunningBatchPartner.GLOBAL, status: PaymentDunningAccountabilityStatus.CONFIRMED, type: PaymentDunningAccountabilityType.RECEIVED_BY_PARTNER, "confirmedDate[ge]": date, "confirmedDate[lt]": CustomDateUtils.sumDays(date, 1), "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_RECEIVED_FEE_PARTNER_EXPENSE, FinancialStatementType.PAYMENT_DUNNING_FINE_REVENUE]]).list()
            if (!accountabilityFineList) return

            Map accountabilityFineListGroupedByDate = accountabilityFineList.groupBy { it.paymentDate }

            Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())

            accountabilityFineListGroupedByDate.each { Date paymentDate, List<PaymentDunningAccountability> accountabilityFineListByDate ->
                BigDecimal fineValueSum = accountabilityFineListByDate.sum { it.fineValue }
                BigDecimal dunningReceivedFeeValue = accountabilityFineListByDate.sum { it.getDunningReceivedFeeValue() }

                FinancialStatement dunningReceivedFineRevenue = financialStatementService.save(FinancialStatementType.PAYMENT_DUNNING_FINE_REVENUE, paymentDate, bank, fineValueSum)
                financialStatementService.saveItems(dunningReceivedFineRevenue, accountabilityFineListByDate)

                FinancialStatement dunningReceivedFeeExpense = financialStatementService.save(FinancialStatementType.PAYMENT_DUNNING_RECEIVED_FEE_PARTNER_EXPENSE, paymentDate, bank, dunningReceivedFeeValue)
                financialStatementService.saveItems(dunningReceivedFeeExpense, accountabilityFineListByDate)
            }
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningAccountabilityReceivedByPartner"])
    }

    private void createForPaymentDunningAccountabilityReceivedDirectly(Date date) {
        Utils.withNewTransactionAndRollbackOnError({
            List<PaymentDunningAccountability> accountabilityList = PaymentDunningAccountability.query([status: PaymentDunningAccountabilityStatus.CONFIRMED, type: PaymentDunningAccountabilityType.RECEIVED_DIRECTLY, "confirmedDate[ge]": date, "confirmedDate[lt]": CustomDateUtils.sumDays(date, 1), "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_RECEIVED_DIRECTLY_FEE_PARTNER_EXPENSE]]).list()

            if (!accountabilityList) return

            Map accountabilityListGroupedByDate = accountabilityList.groupBy { it.confirmedDate }

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

            accountabilityListGroupedByDate.each { Date confirmedDate, List<PaymentDunningAccountability> accountabilityListByDate ->
                Map accountabilityListGroupedByPartner = accountabilityListByDate.groupBy { it.dunning.paymentDunningBatch.partner }
                accountabilityListGroupedByPartner.each { PaymentDunningBatchPartner partner, List<PaymentDunningAccountability> accountabilityListByPartner ->
                    BigDecimal dunningReceivedDirectlyFeeValue = accountabilityListByPartner.sum { it.getDunningReceivedDirectlyFeeValue() }

                    FinancialStatement dunningReceivedDirectlyFeeExpense = financialStatementService.save(FinancialStatementType.PAYMENT_DUNNING_RECEIVED_DIRECTLY_FEE_PARTNER_EXPENSE, confirmedDate, bank, dunningReceivedDirectlyFeeValue)
                    financialStatementService.saveItems(dunningReceivedDirectlyFeeExpense, accountabilityListByPartner)
                }
            }
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningAccountabilityReceivedDirectly"])
    }

    private void createForPaymentDunningCancellationFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_DUNNING_CANCELLATION_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_CANCELLATION_FEE_REVENUE]
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_CANCELLATION_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_CANCELLATION_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank santander = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoList, santander)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningCancellationFee"])
    }

    private void createForPaymentDunningReceivedByPartnerFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_DUNNING_RECEIVED_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_RECEIVED_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_RECEIVED_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_RECEIVED_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            saveDunningFeeStatements(financialTransactionList, financialStatementInfoList)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningReceivedByPartnerFee"])
    }

    private void createForPaymentDunningReceivedDirectlyFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> financialTransactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_DUNNING_RECEIVED_IN_CASH_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_RECEIVED_DIRECTLY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]).list(readOnly: true)
            if (!financialTransactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_RECEIVED_DIRECTLY_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_RECEIVED_DIRECTLY_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            saveDunningFeeStatements(financialTransactionList, financialStatementInfoList)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningReceivedDirectlyFee"])
    }

    private void createForPaymentDunningRequestFee(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.PAYMENT_DUNNING_REQUEST_FEE,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_REVENUE]
            ]).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_REVENUE],
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_CUSTOMER_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningRequestFee"])
    }

    private void createForPaymentDunningRequestFeeRefund(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<FinancialTransaction> transactionList = FinancialTransaction.query([
                transactionType: FinancialTransactionType.CHARGED_FEE_REFUND,
                chargedFeeType: ChargedFeeType.PAYMENT_DUNNING_REQUESTED,
                transactionDate: transactionDate,
                "financialStatementTypeList[notExists]": [FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT]
            ]).list(readOnly: true)
            if (!transactionList) return

            List<Map> financialStatementInfoList = [
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_REFUND_CUSTOMER_BALANCE_CREDIT],
                [financialStatementType: FinancialStatementType.PAYMENT_DUNNING_REQUEST_FEE_REFUND_ASAAS_BALANCE_DEBIT]
            ]

            Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()
            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoList, bank)
        }, [logErrorMessage: "FinancialStatementDunningService - Erro ao executar createForPaymentDunningRequestFeeRefund"])
    }

    private void saveDunningFeeStatements(List<FinancialTransaction> financialTransactionList, List<Map> financialStatementInfoList) {
        BigDecimal dunningFeeSum = BigDecimalUtils.roundDown(financialTransactionList.value.sum(), 2)
        Date transactionDate = financialTransactionList.first().transactionDate
        Bank bank = Bank.query([code: SupportedBank.SANTANDER.code()]).get()

        for (Map financialStatementInfo : financialStatementInfoList) {
            FinancialStatement financialStatement = financialStatementService.save(financialStatementInfo.financialStatementType, transactionDate, bank, dunningFeeSum)
            financialStatementService.saveItems(financialStatement, financialTransactionList)
        }
    }
}
