package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.payment.Payment
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class FinancialStatementTransitoryService {

    def financialStatementService

    public void createFinancialStatementsForTransitory(Date startDate, Date endDate) {
        AsaasLogger.info("FinancialStatementTransitoryService >> createTransitoryForScheduledCustomerRevenue")
        createTransitoryForScheduledCustomerRevenue(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransitoryService >> createTransitoryForPaidCustomerRevenue")
        createTransitoryForPaidCustomerRevenue(startDate, endDate)
        AsaasLogger.info("FinancialStatementTransitoryService >> createTransitoryForReversedCustomerRevenue")
        createTransitoryForReversedCustomerRevenue(startDate, endDate)
    }

    private void createTransitoryForPaidCustomerRevenue(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<Payment> paymentList = Payment.query([
                disableSort: true,
                "creditDate[ge]": startDate,
                "creditDate[le]": endDate,
                status: PaymentStatus.RECEIVED,
                billingTypeList: [BillingType.BOLETO, BillingType.DEPOSIT, BillingType.TRANSFER],
                "financialStatementType[exists]": FinancialStatementType.CUSTOMER_REVENUE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CUSTOMER_REVENUE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list()
            if (!paymentList) return

            Map paymentListGroupByDateMap = paymentList.groupBy { Payment payment -> payment.creditDate}

            paymentListGroupByDateMap.each { Date creditDate, List<Payment> paymentListGroupByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.CUSTOMER_REVENUE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT, creditDate, null, paymentListGroupByDate.netValue.sum())
                financialStatementService.saveItems(financialStatement, paymentListGroupByDate)
            }
        }, [logErrorMessage: "FinancialStatementTransitoryService - Erro ao executar createTransitoryForPaidCustomerRevenue"])
    }

    private void createTransitoryForReversedCustomerRevenue(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<Payment> paymentList = Payment.query([
                disableSort: true,
                "refundedDate[ge]": startDate,
                "refundedDate[le]": endDate,
                status: PaymentStatus.REFUNDED,
                billingTypeList: [BillingType.BOLETO, BillingType.DEPOSIT, BillingType.TRANSFER],
                "financialStatementType[exists]": FinancialStatementType.CUSTOMER_REVENUE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT,
                "financialStatementTypeList[notExists]": [FinancialStatementType.CUSTOMER_REVENUE_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT, FinancialStatementType.CUSTOMER_REVENUE_PAID_CUSTOMER_BALANCE_TRANSITORY_CREDIT]
            ]).list()
            if (!paymentList) return

            Map paymentListGroupByDateMap = paymentList.groupBy { Payment payment -> payment.refundedDate}

            paymentListGroupByDateMap.each { Date refundedDate, List<Payment> paymentListGroupByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.CUSTOMER_REVENUE_REVERSAL_CUSTOMER_BALANCE_TRANSITORY_CREDIT, refundedDate, null, paymentListGroupByDate.netValue.sum())
                financialStatementService.saveItems(financialStatement, paymentListGroupByDate)
            }
        }, [logErrorMessage: "FinancialStatementTransitoryService - Erro ao executar createTransitoryForReversedCustomerRevenue"])
    }

    private void createTransitoryForScheduledCustomerRevenue(Date startDate, Date endDate) {
        Utils.withNewTransactionAndRollbackOnError({
            List<Payment> paymentList = Payment.query([
                disableSort: true,
                "paymentDate[ge]": startDate,
                "paymentDate[lt]": endDate,
                status: PaymentStatus.CONFIRMED,
                paymentDateDifferentFromCreditDate: true,
                billingTypeList: [BillingType.BOLETO, BillingType.DEPOSIT, BillingType.TRANSFER],
                "financialStatementTypeList[notExists]": [FinancialStatementType.CUSTOMER_REVENUE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT]
            ]).list()
            if (!paymentList) return

            Map paymentListGroupByDateMap = paymentList.groupBy { Payment payment -> payment.paymentDate}

            paymentListGroupByDateMap.each { Date paymentDate, List<Payment> paymentListGroupByDate ->
                FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.CUSTOMER_REVENUE_SCHEDULED_CUSTOMER_BALANCE_TRANSITORY_DEBIT, paymentDate, null, paymentListGroupByDate.netValue.sum())
                financialStatementService.saveItems(financialStatement, paymentListGroupByDate)
            }
        }, [logErrorMessage: "FinancialStatementTransitoryService - Erro ao executar createTransitoryForScheduledCustomerRevenue"])
    }
}
