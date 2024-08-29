package com.asaas.service.dashboard

import com.asaas.billinginfo.ChargeType
import com.asaas.paymentstatistic.PaymentDateFilterType
import com.asaas.customeraccount.querybuilder.CustomerAccountQueryBuilder
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.user.User
import com.asaas.payment.PaymentStatus
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.lang.time.DateUtils
import org.hibernate.SQLQuery
import org.hibernate.criterion.CriteriaSpecification

@Transactional
class DashboardStatisticsService {

	def sessionFactory

	def dashboardService
    def grailsApplication

	def getStatistics(Long providerId, Date startDate, Date finishDate, ChargeType chargeType, PaymentDateFilterType paymentDateFilterType, Boolean receivedInCash, Boolean showFinancialInformations) {
		return getStatistics([providerId: providerId, startDate: startDate, finishDate: finishDate, chargeType: chargeType, paymentDateFilterType: paymentDateFilterType, receivedInCash: receivedInCash, showFinancialInformations: showFinancialInformations])
	}

    def getStatistics(Map options) {
        def session = sessionFactory.currentSession
        Map estimatedSubscriptionIncome = [count: 0, value: 0]
        Customer customer = Customer.read(options.providerId)
        User currentUser = UserUtils.getCurrentUser()

        Map returnMap = getCustomerAccountStatistics(options.chargeType, options.paymentDateFilterType, options.providerId, options.startDate, options.finishDate, currentUser)

        if (options.chargeType != ChargeType.DETACHED && options.chargeType != ChargeType.INSTALLMENT) {
            estimatedSubscriptionIncome = dashboardService.getEstimatedIncomeFromSubscriptionBetween(options.providerId, options.startDate, options.finishDate)
        }

        Object[] createdPayments = getResultSet(buildCreatedPaymentsQuery(options.chargeType, options.paymentDateFilterType), options.providerId, options.startDate, options.finishDate, session)
        returnMap.estimatedPayments = createdPayments[0] + estimatedSubscriptionIncome.count

        Object[] overduePayments = getResultSet(buildOverduePaymentsQuery(options.chargeType, options.paymentDateFilterType), options.providerId, options.startDate, options.finishDate, session)
        returnMap.overduePayments = overduePayments[0]

        Object[] receivedPayments = getResultSet(buildReceivedPaymentsQuery([chargeType: options.chargeType, paymentDateFilterType: options.paymentDateFilterType, receivedInCash: options.receivedInCash]), options.providerId, options.startDate, options.finishDate, session)
        returnMap.receivedPayments = receivedPayments[0]

        Object[] confirmedPayments = getResultSet(buildConfirmedPaymentsQuery(options.chargeType, options.paymentDateFilterType), options.providerId, options.startDate, options.finishDate, session)
        returnMap.confirmedPayments = confirmedPayments[0]

        if (currentUser?.hasFinancialModulePermission() || options.showFinancialInformations) {
            returnMap.totalCustomers = CustomerStatistic.getIntegerValue(customer, CustomerStatisticName.TOTAL_CUSTOMER_ACCOUNT_COUNT)
            returnMap.estimatedPaymentsValue = createdPayments[1] + estimatedSubscriptionIncome.value
            returnMap.overduePaymentsValue = overduePayments[1]
            returnMap.receivedPaymentsValue = receivedPayments[1]
            returnMap.confirmedPaymentsValue = confirmedPayments[1]
        }

        return returnMap
    }

	private getResultSet(String sql, Long providerId, Date startDate, Date finishDate, session) {
		def query = session.createSQLQuery(sql)

		if (startDate) query.setDate("startDate", startDate)
		if (finishDate) query.setTimestamp("finishDate", finishDate)

		query.setLong("providerId", providerId)
        query.setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds)

		return query.list().get(0)
	}

	private StringBuilder buildBasePaymentsQuery() {
		StringBuilder builder = new StringBuilder()
		builder.append("select count(1), coalesce(sum(p.value), 0)")
		builder.append("  from payment p")
		builder.append(" where p.deleted = false")
		builder.append("   and p.provider_id = :providerId")
		builder.append("   and p.status not in ('REFUNDED', 'REFUND_REQUESTED') ")
		builder.append(CustomerAccountQueryBuilder.buildWorkspaceRestrictionIfNecessary("p"))

		return builder
	}

	private String buildCreatedPaymentsQuery(ChargeType chargeType, PaymentDateFilterType paymentDateFilterType) {
		StringBuilder builder = buildBasePaymentsQuery()

		if (paymentDateFilterType?.isPaymentDate()) {
			builder.append("and p.payment_date between :startDate and :finishDate ")
		} else if (paymentDateFilterType?.isCreatedDate()) {
			builder.append("and p.date_created between :startDate and :finishDate ")
		} else {
			builder.append("and p.due_date between :startDate and :finishDate ")
		}

		if(chargeType == ChargeType.RECURRENT) {
			builder.append("and exists(select 1 ")
			builder.append("             from subscription_payment sp ")
			builder.append("            where sp.payment_id = p.id) ")
		} else if(chargeType == ChargeType.DETACHED) {
			builder.append("and not exists(select 1 ")
			builder.append("                 from subscription_payment sp ")
			builder.append("                where sp.payment_id = p.id) and p.installment_id is null ")
		} else if (chargeType == ChargeType.INSTALLMENT) {
            builder.append("and p.installment_id is not null ")
		}

 		return builder.toString()
	}

	private String buildConfirmedPaymentsQuery(ChargeType chargeType, PaymentDateFilterType paymentDateFilterType) {
		StringBuilder builder = buildBasePaymentsQuery()

		if (paymentDateFilterType?.isPaymentDate()) {
			builder.append("and p.confirmed_date between :startDate and :finishDate ")
		} else if (paymentDateFilterType?.isCreatedDate()) {
			builder.append("and p.date_created between :startDate and :finishDate ")
		} else {
			builder.append("and p.due_date between :startDate and :finishDate ")
		}

		builder.append("and p.status = '${PaymentStatus.CONFIRMED.toString()}'")

		if(chargeType == ChargeType.RECURRENT) {
			builder.append("and exists(select 1 ")
			builder.append("             from subscription_payment sp ")
			builder.append("            where sp.payment_id = p.id) ")
		} else if(chargeType == ChargeType.DETACHED) {
			builder.append("and not exists(select 1 ")
			builder.append("                 from subscription_payment sp ")
			builder.append("                where sp.payment_id = p.id) and p.installment_id is null ")
		} else if (chargeType == ChargeType.INSTALLMENT) {
			builder.append("and p.installment_id is not null ")
		}

		return builder.toString()
	}

	private String buildReceivedPaymentsQuery(Map options) {
		StringBuilder builder = buildBasePaymentsQuery()

        PaymentDateFilterType paymentDateFilterType = PaymentDateFilterType.convert(options.paymentDateFilterType)

		if (paymentDateFilterType?.isPaymentDate()) {
			builder.append("and p.payment_date between :startDate and :finishDate ")
		} else if (paymentDateFilterType?.isCreatedDate()) {
			builder.append("and p.date_created between :startDate and :finishDate ")
		} else {
			builder.append("and p.due_date between :startDate and :finishDate ")
		}

		if (options.receivedInCash) {
			builder.append("and p.status in ('${PaymentStatus.RECEIVED.toString()}', '${PaymentStatus.RECEIVED_IN_CASH.toString()}')")
		} else {
			builder.append("and p.status = '${PaymentStatus.RECEIVED.toString()}'")
		}

		if (options.chargeType == ChargeType.RECURRENT) {
			builder.append("and exists(select 1 ")
			builder.append("             from subscription_payment sp ")
			builder.append("            where sp.payment_id = p.id) ")
		} else if(options.chargeType == ChargeType.DETACHED) {
			builder.append("and not exists(select 1 ")
			builder.append("                 from subscription_payment sp ")
			builder.append("                where sp.payment_id = p.id) and p.installment_id is null ")
		} else if (options.chargeType == ChargeType.INSTALLMENT) {
			builder.append("and p.installment_id is not null ")
		}

		return builder.toString()
	}

	private String buildOverduePaymentsQuery(ChargeType chargeType, PaymentDateFilterType paymentDateFilterType) {
		StringBuilder builder = buildBasePaymentsQuery()

		if (paymentDateFilterType?.isPaymentDate()) {
			builder.append("and p.payment_date between :startDate and :finishDate ")
		} else if (paymentDateFilterType?.isCreatedDate()) {
			builder.append("and p.date_created between :startDate and :finishDate ")
		} else {
			builder.append("and p.due_date between :startDate and :finishDate ")
		}

		builder.append("and p.status = '${PaymentStatus.OVERDUE.toString()}'")

		if(chargeType == ChargeType.RECURRENT) {
			builder.append("and exists(select 1 ")
			builder.append("             from subscription_payment sp ")
			builder.append("            where sp.payment_id = p.id) ")
		} else if(chargeType == ChargeType.DETACHED) {
			builder.append("and not exists(select 1 ")
			builder.append("                 from subscription_payment sp ")
			builder.append("                where sp.payment_id = p.id) and p.installment_id is null ")
		} else if (chargeType == ChargeType.INSTALLMENT) {
			builder.append("and p.installment_id is not null ")
		}

		return builder.toString()
	}

	public Date calculateStartDateByPeriod(String period, Customer customer) {
		Calendar startDate = CustomDateUtils.getInstanceOfCalendar(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH))

		if ("thisMonth".equals(period)) {
			startDate.set(Calendar.DAY_OF_MONTH, 1)
		} else if ("thisYear".equals(period)) {
			startDate.set(Calendar.DAY_OF_MONTH, 1)
			startDate.set(Calendar.MONTH, Calendar.JANUARY)
		} else if ("fromBeginning".equals(period)) {
			startDate.setTime(customer.dateCreated)
		}

		return startDate.getTime()
	}

	public Date calculateFinishDateByPeriod(String period) {
		Calendar finishDate = CustomDateUtils.getInstanceOfCalendar(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH))

		if ("thisMonth".equals(period)) {
			finishDate.set(Calendar.DAY_OF_MONTH, finishDate.getActualMaximum(Calendar.DAY_OF_MONTH))
		} else if ("thisYear".equals(period)) {
			finishDate.set(Calendar.MONTH, finishDate.getActualMaximum(Calendar.MONTH))
			finishDate.set(Calendar.DAY_OF_MONTH, finishDate.getActualMaximum(Calendar.DAY_OF_MONTH))
		}

		return CustomDateUtils.setTimeToEndOfDay(finishDate).getTime()
	}

    public Date getStartDate(params, Customer customer) {
        if (!params.startDate) {
            return calculateStartDateByPeriod(params.period, customer)
        }

        return CustomDateUtils.toDate(params.startDate)
    }

    public Date getFinishDate(params) {
        if (!params.finishDate) {
            return calculateFinishDateByPeriod(params.period)
        }

        return CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.toDate(params.finishDate))
    }

    private Map getCustomerAccountStatistics(ChargeType chargeType, PaymentDateFilterType paymentDateFilterType, Long customerId, Date startDate, Date finishDate, User currentUser) {
        final Boolean filterOnlyDetachedPayments = chargeType?.isDetached()
        final Boolean filterOnlyInstallments = chargeType?.isInstallment()
        final Boolean filterOnlySubscriptions = chargeType?.isRecurrent()

        String dateFieldToFilter
        String indexToForce
        if (paymentDateFilterType?.isPaymentDate()) {
            dateFieldToFilter = "p.payment_date"
            indexToForce = "payment_provider_payment_date_deleted_order_idx"
        } else if (paymentDateFilterType?.isCreatedDate()) {
            dateFieldToFilter = "p.date_created"
            indexToForce = "payment_provider_date_created_deleted_order_idx"
        } else {
            dateFieldToFilter = "p.due_date"
            indexToForce = "payment_provider_duedate_deleted_order_idx"
        }

        StringBuilder builder = new StringBuilder()
        builder.append("SELECT COUNT(DISTINCT p.customer_account_id) totalCustomers, ")
        builder.append(" COUNT(DISTINCT CASE WHEN p.status = :overdue THEN p.customer_account_id END) AS overdueCustomers ")
        builder.append(" FROM payment p force index(${indexToForce})")
        builder.append(" INNER JOIN customer_account ca ON p.customer_account_id = ca.id ")
        builder.append(" WHERE p.deleted = false ")
        builder.append(" AND p.provider_id = :customerId ")
        builder.append(" AND ca.deleted = false ")
        builder.append(" AND ${dateFieldToFilter} >= :startDate ")
        builder.append(" AND ${dateFieldToFilter} <= :finishDate ")

        if (currentUser?.workspace) {
            builder.append(" AND ca.created_by_id = :currentUserId ")
        }

        if (filterOnlyDetachedPayments) {
            builder.append(" AND p.installment_id IS NULL ")
            builder.append(" AND NOT EXISTS (SELECT 1 FROM subscription_payment sp WHERE sp.payment_id = p.id) ")
        } else if (filterOnlyInstallments) {
            builder.append(" AND p.installment_id IS NOT NULL ")
        } else if (filterOnlySubscriptions) {
            builder.append(" AND EXISTS (SELECT 1 FROM subscription_payment sp WHERE sp.payment_id = p.id) ")
        }

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(builder.toString())
        sqlQuery.setString("overdue", PaymentStatus.OVERDUE.toString())
        sqlQuery.setLong("customerId", customerId)
        sqlQuery.setDate("startDate", startDate.clearTime())
        sqlQuery.setDate("finishDate", CustomDateUtils.setTimeToEndOfDay(finishDate))
        if (currentUser?.workspace) sqlQuery.setLong("currentUserId", currentUser.id)
        sqlQuery.setResultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
        sqlQuery.setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds)

        Map queryResult = sqlQuery.list().first()
        Integer totalCustomers = queryResult.totalCustomers
        Integer overdueCustomers = queryResult.overdueCustomers

        return [
            payingCustomers: totalCustomers - overdueCustomers,
            overdueCustomers: overdueCustomers
        ]
    }
}
