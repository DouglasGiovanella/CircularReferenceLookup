package com.asaas.service.monthlycustomerconfirmedpaymentsummary

import com.asaas.domain.customer.Customer
import com.asaas.domain.monthlycustomerconfirmedpaymentsummary.MonthlyCustomerConfirmedPaymentSummary
import com.asaas.domain.payment.Payment
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import org.hibernate.SQLQuery
import org.hibernate.criterion.CriteriaSpecification

import grails.transaction.Transactional

@Transactional
class MonthlyCustomerConfirmedPaymentSummaryService {

    def sessionFactory

    public List<Long> createAll() {
        List<Long> customerIdList = listLastMonthConfirmedPaymentCustomerId()
        Date firstDayOfMonth = CustomDateUtils.getFirstDayOfLastMonth().clearTime()

        final Integer flushEvery = 100
        final Integer batchSize = 100
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Map confirmedPaymentSummaryInfo = buildLastMonthCustomerConfirmedPaymentSummaryInfo(customerId)
            Customer customer = Customer.read(customerId)

            save(customer, firstDayOfMonth, confirmedPaymentSummaryInfo)
        }, [logErrorMessage: "MonthlyCustomerConfirmedPaymentSummaryService.createAll >> Erro ao criar totalizadores de recebimentos ", appendBatchToLogErrorMessage: true])

        return customerIdList
    }

    private List<Long> listLastMonthConfirmedPaymentCustomerId() {
        StringBuilder builder = new StringBuilder()

        builder.append(" SELECT DISTINCT p.provider_id FROM payment p ")
        builder.append("  LEFT JOIN monthly_customer_confirmed_payment_summary summary ON (summary.customer_id = p.provider_id AND summary.date = :startDate) ")
        builder.append("   WHERE p.credit_date >= :startDate ")
        builder.append("   AND p.credit_date < :endDate ")
        builder.append("   AND p.status IN :hasBeenConfirmedStatusList ")
        builder.append("   AND p.deleted = false ")
        builder.append("   AND summary.id IS NULL ")
        builder.append("  LIMIT :max ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())
        query.setDate("startDate", CustomDateUtils.getFirstDayOfLastMonth().clearTime())
        query.setDate("endDate", CustomDateUtils.getFirstDayOfMonth(new Date()).clearTime())

        query.setParameterList("hasBeenConfirmedStatusList", PaymentStatus.hasBeenConfirmedStatusList().collect { it.toString() })
        query.setInteger("max", 10000)

        return query.list().collect { Utils.toLong(it) }
    }

    private void save(Customer customer, Date firstDayOfMonth, Map confirmedPaymentSummaryInfo) {
        MonthlyCustomerConfirmedPaymentSummary monthlyCustomerConfirmedPaymentSummary = new MonthlyCustomerConfirmedPaymentSummary()

        monthlyCustomerConfirmedPaymentSummary.customer = customer
        monthlyCustomerConfirmedPaymentSummary.date = firstDayOfMonth
        monthlyCustomerConfirmedPaymentSummary.firstCreditDate = confirmedPaymentSummaryInfo.firstCreditDate
        monthlyCustomerConfirmedPaymentSummary.totalQuantity = confirmedPaymentSummaryInfo.totalQuantity
        monthlyCustomerConfirmedPaymentSummary.totalValue = confirmedPaymentSummaryInfo.totalValue
        monthlyCustomerConfirmedPaymentSummary.save(failOnError: true)
    }

    private Map buildLastMonthCustomerConfirmedPaymentSummaryInfo(Long customerId) {
        Date firstDayOfLastMonth = CustomDateUtils.getFirstDayOfLastMonth().clearTime()
        Date firstDayOfCurrentMonth = CustomDateUtils.getFirstDayOfMonth(new Date()).clearTime()

        Map confirmedPaymentSummaryInfo = Payment.createCriteria().get() {
            projections {
                count("id", "totalQuantity")
                sum("value", "totalValue")
                min("creditDate", "firstCreditDate")
            }

            ge("creditDate", firstDayOfLastMonth)
            lt("creditDate", firstDayOfCurrentMonth)
            "in"("status", PaymentStatus.hasBeenConfirmedStatusList())
            eq("provider.id", customerId)
            eq("deleted", false)

            resultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
        }

        return confirmedPaymentSummaryInfo
    }
}
