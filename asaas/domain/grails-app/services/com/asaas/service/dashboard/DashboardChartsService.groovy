package com.asaas.service.dashboard

import com.asaas.billinginfo.ChargeType
import com.asaas.paymentstatistic.PaymentDateFilterType
import com.asaas.customeraccount.querybuilder.CustomerAccountQueryBuilder
import com.asaas.domain.subscription.Subscription
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.lang.time.DateUtils

@Transactional
class DashboardChartsService {

	static int MAX_MONTHLY_RESULTS = 24

	static int MAX_DAILY_RESULTS = 60

    private static Integer LIMIT_OF_SUBSCRIPTIONS_TO_CALCULATE = 10000

	def sessionFactory

	def dashboardService

	def getIncomeByDay(Map options) {
		List<Object[]> queryResults = getQueryResult(buildIncomeByDayQuery(options), options.providerId, options.startDate, options.finishDate)

		Calendar period = Calendar.getInstance();
		period.setTime(DateUtils.truncate(options.finishDate, Calendar.DAY_OF_MONTH))

		List<HashMap<String, Double>> resultList = new ArrayList<HashMap<String, Double>>()

        List<Map> subscriptionDataList = []
        if (!options.receivedPaymentsOnly && options.chargeType != ChargeType.DETACHED && options.chargeType != ChargeType.INSTALLMENT) {
            subscriptionDataList.addAll(
                Subscription.query([
                    columnList: ["cycle", "endDate", "expirationDay", "nextDueDate", "value"],
                    customerId: options.providerId
                ]).list(max: LIMIT_OF_SUBSCRIPTIONS_TO_CALCULATE) as List<Map>
            )
        }

		int days = 0
		while (!period.getTime().before(options.startDate) && days <= MAX_DAILY_RESULTS) {
			Boolean exists = false
			for (Object[] result : queryResults) {
				if ( period.getTime().clearTime().equals(Date.parse("yyyy-M-d", result[0])) ) {
					resultList.add([x: result[0], y: result[1]])
					exists = true
				}
			}

			if (!exists) resultList.add([x: period.format("yyyy-M-d"), y: 0])

            if (!options.receivedPaymentsOnly && options.chargeType != ChargeType.DETACHED) {
                resultList.get(resultList.size() -1).y = resultList.get(resultList.size() -1).y + dashboardService.getEstimatedIncomeFromSubscriptionByDay(period.getTime(), options.startDate, options.finishDate, subscriptionDataList)
            }

			period.add(Calendar.DAY_OF_MONTH, -1)
            period.set(Calendar.HOUR_OF_DAY, 0)
			days++
		}

		return resultList
	}

	def getIncomeByMonth(Map options) {
		List<Object[]> queryResults = getQueryResult(buildIncomeByMonthQuery(options), options.providerId, options.startDate, options.finishDate)

		Calendar period = Calendar.getInstance();
		period.setTime(DateUtils.truncate(options.finishDate, Calendar.DAY_OF_MONTH))
		period.set(Calendar.DAY_OF_MONTH, 1)

		Calendar startDateCalendar = CustomDateUtils.getInstanceOfCalendar(DateUtils.truncate(options.startDate, Calendar.DAY_OF_MONTH))
		startDateCalendar.set(Calendar.DAY_OF_MONTH, 1)

		List<HashMap<String, Double>> resultList = new ArrayList<HashMap<String, Double>>()

        List<Map> subscriptionDataList = []
        if (!options.receivedPaymentsOnly && options.chargeType != ChargeType.DETACHED && options.chargeType != ChargeType.INSTALLMENT) {
            subscriptionDataList.addAll(
                Subscription.query([
                    columnList: ["cycle", "endDate", "expirationDay", "nextDueDate", "value"],
                    customerId: options.providerId
                ]).list(max: LIMIT_OF_SUBSCRIPTIONS_TO_CALCULATE) as List<Map>
            )
        }

		int months = 0
		while (!period.before(startDateCalendar) && months <= MAX_MONTHLY_RESULTS ) {
			Boolean exists = false
			for (Object[] result : queryResults) {
				if ( period.getTime().equals(Date.parse("yyyy-M-dd", result[0])) ) {
					resultList.add([x: result[0], y: result[1]])
					exists = true
				}
			}

			if (!exists) resultList.add([x: period.format("yyyy-M-dd"), y: 0])

            if (!options.receivedPaymentsOnly && options.chargeType != ChargeType.DETACHED) {
                resultList.get(resultList.size() -1).y = resultList.get(resultList.size() -1).y + dashboardService.getEstimatedIncomeFromSubscriptionByMonth(period.getTime(), options.startDate, options.finishDate, subscriptionDataList)
            }

			period.add(Calendar.MONTH, -1)
			months++
		}

		return resultList
	}

	private List<Object[]> getQueryResult(String sql, providerId, startDate, finishDate) {
		def session = sessionFactory.currentSession
		def query = session.createSQLQuery(sql)
		query.setDate("startDate", startDate)
		query.setDate("finishDate", finishDate)
		query.setLong("providerId", providerId)

		return query.list()
	}

	private String buildIncomeByMonthQuery(Map options) {
		return buildIncomeQuery(false, options)
	}

	private String buildIncomeByDayQuery(Map options) {
		return buildIncomeQuery(true, options)
	}

    private String buildIncomeQuery(Boolean byDay, Map options) {
        String selectedDateField = buildIncomeQueryDateField(options.paymentDateFilterType)
        String formatedDateField = formatIncomeQueryDate(byDay, selectedDateField)

        StringBuilder builder = new StringBuilder()
		builder.append("select ${formatedDateField}, sum(p.value)")
		builder.append("  from payment p ")

        builder.append(" where p.deleted = false")
		builder.append("   and p.provider_id = :providerId")
        builder.append(CustomerAccountQueryBuilder.buildWorkspaceRestrictionIfNecessary("p"))

        builder.append("   and ${selectedDateField} between :startDate and :finishDate ")

        if (options.receivedPaymentsOnly) {
			if (options.receivedInCash) {
				builder.append(" and p.status in ('CONFIRMED', 'RECEIVED', 'RECEIVED_IN_CASH')")
			} else {
				builder.append(" and p.status in ('CONFIRMED', 'RECEIVED')")
			}
		} else {
            builder.append(" and p.status not in ('REFUNDED', 'REFUND_REQUESTED')")
        }

        if (options.chargeType == ChargeType.RECURRENT) {
			builder.append(" and exists (select 1 from subscription_payment sp where sp.payment_id = p.id) ")
		} else if (options.chargeType == ChargeType.DETACHED) {
			builder.append(" and not exists (select 1 from subscription_payment sp where sp.payment_id = p.id) and p.installment_id is null ")
		} else if (options.chargeType == ChargeType.INSTALLMENT) {
            builder.append(" and p.installment_id is not null ")
        }

        builder.append(" group by ${formatedDateField} ")
        builder.append(" order by ${formatedDateField} desc")

        if (byDay) {
            builder.append(" limit ${MAX_DAILY_RESULTS}")
        } else {
            builder.append(" limit ${MAX_MONTHLY_RESULTS}")
        }

        return builder.toString()
    }

    private String buildIncomeQueryDateField(PaymentDateFilterType paymentDateFilterType) {
        if (paymentDateFilterType.isPaymentDate()) {
            return "p.payment_date"
        } else if (paymentDateFilterType.isCreatedDate()) {
            return "p.date_created"
        } else {
            return "p.due_date"
        }
    }

    private String formatIncomeQueryDate(Boolean byDay, String dateField) {
        if (byDay) {
            return "DATE_FORMAT(${dateField}, '%Y-%m-%d')"
        } else {
            return "CONCAT(DATE_FORMAT(${dateField}, '%Y-%m'), '-01')"
        }
    }
}
