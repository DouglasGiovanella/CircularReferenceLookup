package com.asaas.service.financialtransaction

import com.asaas.domain.customer.Customer
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialtransaction.FinancialTransactionListVo
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

import org.hibernate.criterion.CriteriaSpecification

@Transactional
class FinancialTransactionListService {

    def grailsApplication

    public FinancialTransactionListVo list(Customer customer, Map search) {
        FinancialTransactionListVo listVo = new FinancialTransactionListVo()
        listVo.customer = customer
        listVo.initialDate = search.initialDate
        listVo.finalDate = search.finalDate
        listVo.initialBalance = 0
        listVo.finalBalance = 0

        listVo.transactionList = listCriteria(customer, search)

        if (!listVo.transactionList) {
            Date initialDate = search.initialDate
            Long startId = search.startId
            if (startId) {
                Date lastTransactionDate = FinancialTransaction.query([column: "transactionDate", customerId: customer.id, "id[le]": search.startId, sort: "id", order: "desc"]).get()
                if (lastTransactionDate && lastTransactionDate < initialDate) {
                    initialDate = lastTransactionDate
                } else {
                    startId = null
                }
            }

            listVo.initialBalance = calculateBalanceUntilSpecificTransaction(customer, startId, initialDate)
            listVo.finalBalance = listVo.initialBalance
            return listVo
        }

        if (search.order == "desc") {
            calculateAndSetBalanceForEachItem(customer, listVo, true)
        } else {
            calculateAndSetBalanceForEachItem(customer, listVo, false)
        }

        return listVo
    }

    public List listCriteria(Customer customer, Map search) {
        return FinancialTransaction.createCriteria().list(max: search.max, offset: search.offset, timeout: grailsApplication.config.asaas.query.defaultTimeoutInSeconds) {
            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)

            projections {
                for (String column : ["id", "transactionDate", "value", "description", "transactionType"]) {
                    property("${column}", "${column}")
                }

                if (Boolean.valueOf(search.includePaymentId)) {
                    property("payment.id", "paymentId")
                }

                if (Boolean.valueOf(search.includeReversedTransactionId)) {
                    property("reversedTransaction.id", "reversedTransactionId")
                }

                if (Boolean.valueOf(search.includeReceivableAnticipationId)) {
                    property("receivableAnticipation.id", "receivableAnticipationId")
                }

                if (Boolean.valueOf(search.includeInvoiceId)) {
                    property("invoice.id", "invoiceId")
                }

                if (Boolean.valueOf(search.includeInternalTransferId)) {
                    property("internalTransfer.id", "internalTransferId")
                }
            }

            eq("provider", customer)
            eq("deleted", false)

            if (search.startId) ge("id", search.startId)
            if (search.finishId) le("id", search.finishId)
            if (search.initialDate) ge("transactionDate", search.initialDate)
            if (search.finalDate) le("transactionDate", search.finalDate)

            order("id", search.order ?: "asc")
        }
    }

    private void calculateAndSetBalanceForEachItem(Customer customer, FinancialTransactionListVo listVo, Boolean reversedList) {
        List sortedList = reversedList ? listVo.transactionList.reverse() : listVo.transactionList

        listVo.initialBalance = calculateBalanceUntilSpecificTransaction(customer, sortedList.first().id, sortedList.first().transactionDate)
        listVo.finalBalance = listVo.initialBalance

        for (transaction in sortedList) {
            listVo.finalBalance += transaction.value
            transaction.balance = listVo.finalBalance
        }
    }

    private BigDecimal calculateBalanceUntilSpecificTransaction(Customer customer, Long transactionId, Date transactionDate) {
        Map queryParams = [provider: customer]
        if (transactionId) queryParams."id[lt]" = transactionId

        if (transactionDate) {
            if (!transactionId) queryParams."transactionDate[lt]" = transactionDate

            Date oneDayBefore = CustomDateUtils.sumDays(transactionDate, -1)
            Map oneDayBeforeConsolidatedBalanceInfoMap = CustomerDailyBalanceConsolidation.query([columnList: ["consolidatedBalance", "lastFinancialTransactionId"], customer: customer, consolidationDate: oneDayBefore]).get()

            if (oneDayBeforeConsolidatedBalanceInfoMap) {
                BigDecimal consolidatedBalanceOneDayBefore =  oneDayBeforeConsolidatedBalanceInfoMap.consolidatedBalance

                if (oneDayBeforeConsolidatedBalanceInfoMap.lastFinancialTransactionId) {
                    queryParams."id[gt]" = oneDayBeforeConsolidatedBalanceInfoMap.lastFinancialTransactionId
                    return consolidatedBalanceOneDayBefore + getFinancialTransactionSumValueWithFixedIndex(queryParams)
                }

                return consolidatedBalanceOneDayBefore + getFinancialTransactionSumValueWithFixedIndex(queryParams + ["transactionDate[gt]": oneDayBefore])
            }
        }

        return getFinancialTransactionSumValueWithFixedIndex(queryParams)
    }

    private BigDecimal getFinancialTransactionSumValueWithFixedIndex(Map queryParams) {
        Boolean mustForceIndex = queryParams.containsKey("transactionDate[lt]") || queryParams.containsKey("transactionDate[gt]")

        FinancialTransaction.withSession { session ->
            org.hibernate.SQLQuery query = session.createSQLQuery(" SELECT coalesce(sum(this_.value), 0) as sumValue " +
                " FROM financial_transaction this_ " +
                (mustForceIndex ? " FORCE INDEX (financial_transaction_provider_transactiondate_idx) " : "") +
                " WHERE this_.provider_id = :customerId " +
                " AND this_.deleted = false " +
                (queryParams."id[lt]" ? " AND this_.id < :financialTransactionIdLt " : "") +
                (queryParams."id[gt]" ? " AND this_.id > :financialTransactionIdGt " : "") +
                (queryParams."transactionDate[lt]" ? " AND this_.transaction_date < :transactionDateLt " : "") +
                (queryParams."transactionDate[gt]" ? " AND this_.transaction_date > :transactionDateGt " : "")
            )

            query.setLong("customerId", queryParams.provider.id)

            if (queryParams."id[lt]") query.setLong("financialTransactionIdLt", queryParams."id[lt]")
            if (queryParams."id[gt]") query.setLong("financialTransactionIdGt", queryParams."id[gt]")
            if (queryParams."transactionDate[lt]") query.setDate("transactionDateLt", queryParams."transactionDate[lt]")
            if (queryParams."transactionDate[gt]") query.setDate("transactionDateGt", queryParams."transactionDate[gt]")

            return query.list().get(0)
        }
    }

}
