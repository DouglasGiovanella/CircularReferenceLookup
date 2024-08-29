package com.asaas.service.integration.bacen.simba

import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.simba.SimbaFinancialTransactionRequestRelationship
import com.asaas.domain.simba.SimbaFinancialTransactionRequestRelationshipTransaction
import com.asaas.integration.bacen.simba.FinancialTransactionSimbaDetailVo
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class FinancialTransactionSimbaDetailListService {

    private static final String MAX_FINANCIAL_TRANSACTIONS = 250

    def bankSlipService

    public List<FinancialTransactionSimbaDetailVo> collectSimbaFinancialTransactionDetail(SimbaFinancialTransactionRequestRelationship relationship, Date initialDate, Date finalDate) {
        List<FinancialTransactionSimbaDetailVo> transactionsWithOriginDestinationDetailList = []

        SimbaFinancialTransactionRequestRelationshipTransaction lastCollectedTransaction = SimbaFinancialTransactionRequestRelationshipTransaction.query(["relationshipId": relationship.id, sort: "id", order: "desc"]).get()

        Map queryParams = buildQueryParam(lastCollectedTransaction, relationship.customerId, initialDate, finalDate)
        List<FinancialTransaction> financialTransactionsList = FinancialTransaction.query(queryParams)
                                                                                    .list([max: FinancialTransactionSimbaDetailListService.MAX_FINANCIAL_TRANSACTIONS,
                                                                                           readOnly: true])

        AsaasLogger.info("FinancialTransactionSimbaDetailListService.collectSimbaFinancialTransactionDetail - queryParams: ${queryParams}, Financial Transactions encontradas: [${financialTransactionsList.collect { it.id }}]")

        if (!financialTransactionsList) return transactionsWithOriginDestinationDetailList

        BigDecimal balance = lastCollectedTransaction?.balance ?: calculatePreviousBalance(relationship.customerId, financialTransactionsList.first().id, initialDate)

        for (FinancialTransaction financialTransaction : financialTransactionsList) {
            try {
                balance += financialTransaction.value
                FinancialTransactionSimbaDetailVo financialTransactionSimbaDetailVo = new FinancialTransactionSimbaDetailVo(financialTransaction, balance)

                Payment payment = financialTransaction.getRelatedPayment()
                if (payment && payment.boletoBank) {
                    financialTransactionSimbaDetailVo.barCode = bankSlipService.getLinhaDigitavel(payment)
                }
                transactionsWithOriginDestinationDetailList.add(financialTransactionSimbaDetailVo)
            } catch (Exception e) {
                AsaasLogger.error("FinancialTransactionSimbaDetailListService.collectSimbaFinancialTransactionDetail >> Coletando transações - Transação [${financialTransaction.id}] com erro:\r\n ${e.message}", e)
                throw e
            }
        }

        return transactionsWithOriginDestinationDetailList
    }

    private Map buildQueryParam(SimbaFinancialTransactionRequestRelationshipTransaction lastCollectedTransaction, Long customerId, Date initialDate, Date finalDate) {
        Map query = ["customerId": customerId, "transactionDate[le]": finalDate]

        if (lastCollectedTransaction) {
            query += ["id[gt]": Long.valueOf(lastCollectedTransaction.financialTransactionId)]
            return query
        }

        query += ["transactionDate[ge]": initialDate]
        return query
    }

    private BigDecimal calculatePreviousBalance(Long customerId, Long firstFinancialTransactionId, Date initialDate) {
        BigDecimal balance = CustomerDailyBalanceConsolidation.query([column: "consolidatedBalance",
                                                                      customerId: customerId,
                                                                      "consolidationDate[lt]": initialDate,
                                                                      sort: "consolidationDate",
                                                                      order: "desc"]).get()

        if (balance) return balance

        balance = FinancialTransaction.sumValue([customerId: customerId,
                                            "id[lt]": firstFinancialTransactionId]).get()

        return balance
    }
}
