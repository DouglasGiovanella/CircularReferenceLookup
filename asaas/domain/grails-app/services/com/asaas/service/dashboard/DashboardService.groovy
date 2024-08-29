package com.asaas.service.dashboard

import com.asaas.domain.subscription.Subscription
import com.asaas.product.Cycle
import com.asaas.subscription.SubscriptionStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.lang.time.DateUtils

@Transactional
class DashboardService {

    def subscriptionService

    public BigDecimal getEstimatedIncomeFromSubscriptionByMonth(Date month, Date startDate, Date finishDate, List<Map> subscriptionDataList) {
        if (!subscriptionDataList) return 0.0

        Boolean isPastMonth = CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(new Date(), month) < 0
        if (isPastMonth) return 0.0

        BigDecimal estimatedIncome = 0.0
        for (Map subscriptionData : subscriptionDataList) {
            subscriptionService.forEachFutureSubscriptionPayment(
                startDate,
                finishDate,
                subscriptionData.nextDueDate as Date,
                subscriptionData.endDate as Date,
                subscriptionData.cycle as Cycle,
                subscriptionData.expirationDay as Integer,
                { Date currentDueDate ->
                    Boolean isSameMonth = CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(month, currentDueDate) == 0
                    if (!isSameMonth) return

                    estimatedIncome += subscriptionData.value
                }
            )
        }

        return estimatedIncome
    }

    public BigDecimal getEstimatedIncomeFromSubscriptionByDay(Date day, Date startDate, Date finishDate, List<Map> subscriptionDataList) {
        if (!subscriptionDataList) return 0.0

        Calendar dayToSummarize = CustomDateUtils.getInstanceOfCalendar(day)
        if (dayToSummarize.before(DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH))) return 0

        BigDecimal estimatedIncome = 0.0
        for (Map subscriptionData : subscriptionDataList) {
            subscriptionService.forEachFutureSubscriptionPayment(
                startDate,
                finishDate,
                subscriptionData.nextDueDate as Date,
                subscriptionData.endDate as Date,
                subscriptionData.cycle as Cycle,
                subscriptionData.expirationDay as Integer,
                { Date currentDueDate ->
                    Boolean isSameDay = currentDueDate.clearTime() == day.clearTime()
                    if (!isSameDay) return

                    estimatedIncome += subscriptionData.value
                }
            )
        }

        return estimatedIncome
    }

    public Map getEstimatedIncomeFromSubscriptionBetween(Long customerId, Date startDate, Date finishDate) {
        final Integer limitOfItemsToCalculate = 10000
        List<Map> subscriptionDataList = Subscription.query([
            columnList: ["cycle", "endDate", "expirationDay", "nextDueDate", "value"],
            customerId: customerId,
            status: SubscriptionStatus.ACTIVE,
            excludeWithCustomerAccountDeleted: true,
            planIsNull: true,
            valueGreaterThan: 0.0
        ]).list(max: limitOfItemsToCalculate)

        BigDecimal incomeValue = 0.0
        Integer incomeCount = 0
        for (Map subscriptionData : subscriptionDataList) {
            subscriptionService.forEachFutureSubscriptionPayment(
                startDate,
                finishDate,
                subscriptionData.nextDueDate as Date,
                subscriptionData.endDate as Date,
                subscriptionData.cycle as Cycle,
                subscriptionData.expirationDay as Integer,
                {
                    incomeValue += subscriptionData.value
                    incomeCount++
                }
            )
        }

        return [value: incomeValue, count: incomeCount]
    }
}
