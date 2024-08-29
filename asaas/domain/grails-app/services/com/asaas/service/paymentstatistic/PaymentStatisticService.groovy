package com.asaas.service.paymentstatistic

import com.asaas.billinginfo.ChargeType
import com.asaas.customeraccount.querybuilder.CustomerAccountQueryBuilder
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentDunningStatus
import com.asaas.payment.PaymentStatus
import com.asaas.paymentstatistic.PaymentDateFilterType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.hibernate.transform.AliasToEntityMapResultTransformer

@Transactional
class PaymentStatisticService {

	def sessionFactory
    def grailsApplication
    def grailsLinkGenerator

	public Map buildPaymentStatisticsMap(Map options) {
        return [
            receivedPayment: getReceivedPaymentStatisticMap(options),
            confirmedPayment: getConfirmedPaymentStatisticMap(options),
            pendingPayment: getPendingPaymentStatisticMap(options),
            overduePayment: getOverduePaymentStatisticMap(options)
        ]
    }

    public List<Map> buildReceiptCalendarMap(Map options) {
        if (!options.startDate || !options.finishDate) {
            throw new BusinessException("O periodo deve ser informado")
        }

        if (!options.providerId) {
            throw new BusinessException("O cliente deve ser informado")
        }

        Date startDate = CustomDateUtils.fromString(options.startDate).clearTime()
        Date finishDate = CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.fromString(options.finishDate))

        final Integer maxDaysToFilter = 31

        if (CustomDateUtils.calculateDifferenceInDays(startDate, finishDate) > maxDaysToFilter) {
            throw new BusinessException("Informe período máximo de ${maxDaysToFilter} dias para o calendário de recebimentos.")
        }

        List<Map> calendarInfoMapList = getCalendarConfirmedAndReceivedPaymentsInfoList(Utils.toLong(options.providerId), startDate, finishDate)
        return calendarInfoMapList
    }

    private List<Map> getCalendarConfirmedAndReceivedPaymentsInfoList(Long customerId, Date startDate, Date finishDate) {
        StringBuilder builder = new StringBuilder()
        builder.append(" select coalesce(sum(cast(value as DECIMAL(19,2))),0) as totalValue, status, credit_date as date ")
        builder.append("  from ( ")
        builder.append(" select p.value, p.status as status, date(p.credit_date) as credit_date ")
        builder.append("  from payment p")
        builder.append(" where p.provider_id = :customerId")
        builder.append("   and p.deleted = false")
        builder.append("   and p.anticipated = false ")
        builder.append("   and p.credit_date >= :startDate")
        builder.append("   and p.credit_date <= :finishDate")
        builder.append("   and p.status in :statusList")
        builder.append(" union all")
        builder.append(" select ra.total_value as value, :received as status, date(ra.credit_date) as credit_date ")
        builder.append("  from receivable_anticipation ra")
        builder.append(" where ra.credit_date >= :startDate")
        builder.append("   and ra.credit_date <= :finishDate")
        builder.append("   and ra.customer_id = :customerId")
        builder.append("   and ra.deleted = false")
        builder.append(" ) receiptCalendarQuery ")
        builder.append(" group by 2, 3")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setDate("startDate", startDate)
        query.setDate("finishDate", finishDate)
        query.setParameterList("statusList", [PaymentStatus.CONFIRMED.toString(), PaymentStatus.RECEIVED.toString(), PaymentStatus.DUNNING_RECEIVED.toString()])
        query.setString("received", PaymentStatus.RECEIVED.toString())
        query.setLong("customerId", customerId)

        query.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)
        query.setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds)

        return query.list()
    }

    private Map getReceivedPaymentStatisticMap(Map options) {
        List<String> receivedStatusStringList = [
            PaymentStatus.RECEIVED,
            PaymentStatus.REFUND_REQUESTED,
            PaymentStatus.CHARGEBACK_REQUESTED,
            PaymentStatus.CHARGEBACK_DISPUTE,
            PaymentStatus.AWAITING_CHARGEBACK_REVERSAL,
            PaymentStatus.DUNNING_RECEIVED
        ].collect { it.toString() }

        if (options.receivedInCash) {
            receivedStatusStringList += PaymentStatus.RECEIVED_IN_CASH.toString()
        }

        Map receivedParams = options + [paymentStatusList: receivedStatusStringList, "paymentDate[isNotNull]": true]
        String receivedQueryFilter = buildReceivedPaymentsQueryFilter(receivedParams)

        return buildStatisticMap(receivedParams, receivedQueryFilter)
    }

    private Map getConfirmedPaymentStatisticMap(Map options) {
        List<String> confirmedStatusStringList = [
            PaymentStatus.CONFIRMED,
            PaymentStatus.CHARGEBACK_REQUESTED,
            PaymentStatus.CHARGEBACK_DISPUTE
        ].collect { it.toString() }

        Map confirmedParams = options + [paymentStatusList: confirmedStatusStringList, "paymentDate[isNull]": true]
        String confirmedQueryFilter = buildConfirmedPaymentsQueryFilter(confirmedParams)

        if (PaymentDateFilterType.convert(options.paymentDateFilterType)?.isPaymentDate()) confirmedParams.paymentDateFilterType = PaymentDateFilterType.CONFIRMED_DATE

        return buildStatisticMap(confirmedParams, confirmedQueryFilter)
    }

    private Map getPendingPaymentStatisticMap(Map options) {
        List<String> pendingStatusStringList = [
            PaymentStatus.PENDING,
            PaymentStatus.AUTHORIZED,
            PaymentStatus.AWAITING_RISK_ANALYSIS
        ].collect { it.toString() }

        Date dueDateStart = CustomDateUtils.fromString(options.startDate)
        if (dueDateStart?.before(new Date())) {
            dueDateStart = new Date().clearTime()
        }

        Map pendingParams = options + [paymentStatusList: pendingStatusStringList, dueDateStart: dueDateStart, "paymentDate[isNull]": true]
        String pendingQueryFilter = buildPendingPaymentsQueryFilter(pendingParams)

        return buildStatisticMap(pendingParams, pendingQueryFilter)
    }

    private Map getOverduePaymentStatisticMap(Map options) {
        List<String> overdueStatusStringList = [
            PaymentStatus.OVERDUE,
            PaymentStatus.DUNNING_REQUESTED,
            PaymentStatus.AWAITING_RISK_ANALYSIS,
            PaymentStatus.AUTHORIZED
        ].collect { it.toString() }

        Date dueDateFinish = CustomDateUtils.fromString(options.finishDate)
        if (dueDateFinish?.after(new Date())) {
            dueDateFinish = new Date().clearTime()
        }

        Map overdueParams = options + [paymentStatusList: overdueStatusStringList, dueDateFinish: dueDateFinish, "paymentDate[isNull]": true]
        String overdueQueryFilter = buildOverduePaymentsQueryFilter(options)

        return buildStatisticMap(overdueParams, overdueQueryFilter)
    }

    private Map buildStatisticMap(Map queryParams, String statusFilterQuery) {
        List<Map> paymentStatisticList = buildPaymentQuery(statusFilterQuery, queryParams)

        Map paymentStatisticMap = getPaymentStatisticResultMap(paymentStatisticList)
        paymentStatisticMap.clientNumber = countCustomerAccount(statusFilterQuery, queryParams)

        Map datailedListParams = buildPaymentListFilterMap(queryParams)
        paymentStatisticMap = buildStatisticDetailedListParams(paymentStatisticMap, datailedListParams)

        return paymentStatisticMap
    }

    private Map buildStatisticDetailedListParams(Map paymentStatisticMap, Map queryParams) {
        paymentStatisticMap.paymentListLink = grailsLinkGenerator.link(controller: "payment", action: "list", params: queryParams)
        paymentStatisticMap.customerAccountListLink = grailsLinkGenerator.link(controller: "customerAccount", action: "listFromDashboard", params: queryParams)

        return paymentStatisticMap
    }

    private Map getPaymentStatisticResultMap(List paymentList) {
        if (!paymentList) return [totalValue: 0, netValue: 0, chartObject: [:], paymentNumber: 0, clientNumber: 0]

        Map paymentStatisticMap = [:]
        paymentStatisticMap.totalValue = paymentList.sum { it.totalValue }
        paymentStatisticMap.netValue = paymentList.sum { it.totalNetValue }
        paymentStatisticMap.chartObject = paymentList.collectEntries { [it.billingType, it.totalValue] }
        paymentStatisticMap.paymentNumber = paymentList.sum { it.paymentCount }

        return paymentStatisticMap
    }

    private List<Map> buildPaymentQuery(String statusFilterQuery, Map options) {
        StringBuilder builder = new StringBuilder()

        if (options.receivedInCash) {
            builder.append(" select case when p.status = '${PaymentStatus.RECEIVED_IN_CASH}' then p.status else p.billing_type end billingType, ")
        } else {
            builder.append(" select billing_type as billingType, ")
        }
        builder.append(" coalesce(sum(cast(value as DECIMAL(19,2))),0) totalValue, coalesce(sum(cast(net_value as DECIMAL(19,2))),0) totalNetValue, count(1) paymentCount ")
        builder.append(buildBaseQuery(statusFilterQuery, options))
        builder.append(" group by 1")

        SQLQuery query = createQueryAndApplyFilters(builder, options)

        return query.list()
    }

    private Integer countCustomerAccount(String statusFilterQuery, Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append(" select count(distinct p.customer_account_id) customerAccountCount ")
        builder.append(buildBaseQuery(statusFilterQuery, options))
        SQLQuery query = createQueryAndApplyFilters(builder, options)

        return Utils.toInteger(query.list()?.first()?.customerAccountCount) ?: 0
    }

    private SQLQuery createQueryAndApplyFilters(StringBuilder builder, Map options) {
        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        if (options.startDate) query.setDate("startDate", CustomDateUtils.fromString(options.startDate))
        if (options.finishDate) query.setDate("finishDate", CustomDateUtils.fromString(options.finishDate))
        if (options.paymentStatusList) query.setParameterList("statusList", options.paymentStatusList)
        if (options.dueDateStart) query.setDate("dueDateStart", options.dueDateStart)
        if (options.dueDateFinish) query.setDate("dueDateFinish", options.dueDateFinish)

        query.setLong("providerId", options.providerId)
        query.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)
        query.setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds)

        return query
    }

    private String buildBaseQuery(String statusFilterQuery, Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append("  from payment p")
        builder.append(" where p.deleted = false")
        builder.append("   and p.provider_id = :providerId")
        builder.append("   and p.status IN :statusList")
        builder.append(CustomerAccountQueryBuilder.buildWorkspaceRestrictionIfNecessary("p"))
        builder.append(statusFilterQuery.toString())

        ChargeType chargeType = ChargeType.convert(options.chargeType)
        if (chargeType?.isRecurrent()) {
            builder.append(" and exists(select 1 ")
            builder.append("             from subscription_payment sp ")
            builder.append("            where sp.payment_id = p.id) ")
        } else if (chargeType?.isDetached()) {
            builder.append(" and not exists(select 1 ")
            builder.append("                 from subscription_payment sp ")
            builder.append("                where sp.payment_id = p.id) and p.installment_id is null ")
        } else if (chargeType?.isInstallment()) {
            builder.append(" and p.installment_id is not null ")
        }

        if (options.notAnticipated) {
            builder.append(" and p.anticipated is false ")
        }

        if (options.ignoreDunning) {
            builder.append(" and not exists (select 1 from payment_dunning pd where pd.payment_id = p.id and pd.deleted = false and pd.status not in ('${PaymentDunningStatus.CANCELLED}', '${PaymentDunningStatus.DENIED}'))")
        }

        return builder.toString()
    }

    private String buildReceivedPaymentsQueryFilter(Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append(" and p.payment_date is not null")
        builder.append(buildDateFilter(PaymentDateFilterType.convert(options.paymentDateFilterType)))

        return builder.toString()
    }

    private String buildConfirmedPaymentsQueryFilter(Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append(" and p.payment_date is null")
        builder.append(" and p.confirmed_date is not null")

        PaymentDateFilterType paymentDateFilterType = PaymentDateFilterType.convert(options.paymentDateFilterType)

        if (paymentDateFilterType?.isPaymentDate()) paymentDateFilterType = PaymentDateFilterType.CONFIRMED_DATE
        builder.append(buildDateFilter(paymentDateFilterType))

        return builder.toString()
    }

    private String buildPendingPaymentsQueryFilter(Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append(" and p.payment_date is null")
        builder.append(buildDateFilter(PaymentDateFilterType.convert(options.paymentDateFilterType)))

        builder.append(" and p.due_date >= :dueDateStart")

        return builder.toString()
    }

    private String buildOverduePaymentsQueryFilter(Map options) {
        StringBuilder builder = new StringBuilder()
        builder.append(" and p.payment_date is null")
        builder.append(buildDateFilter(PaymentDateFilterType.convert(options.paymentDateFilterType)))

        if (options.dueDateFinish < new Date().clearTime()) {
            builder.append(" and p.due_date <= :dueDateFinish")
        } else {
            builder.append(" and p.due_date < :dueDateFinish")
        }

        return builder.toString()
    }

    private String buildDateFilter(PaymentDateFilterType paymentDateFilterType) {
        if (paymentDateFilterType?.isPaymentDate()) return " and p.payment_date between :startDate and :finishDate"
        if (paymentDateFilterType?.isConfirmedDate()) return " and p.confirmed_date between :startDate and :finishDate"
        if (paymentDateFilterType?.isCreatedDate()) return " and p.date_created between :startDate and :finishDate "

        return (" and p.due_date between :startDate and :finishDate ")
    }

    private Map buildPaymentListFilterMap(Map options) {
        Map paymentListFilter = [chargeType: options.chargeType]

        if (options.containsKey("paymentDate[isNull]")) paymentListFilter."paymentDate[isNull]" = true
        if (options.containsKey("paymentDate[isNotNull]")) paymentListFilter."paymentDate[isNotNull]" = true

        PaymentDateFilterType paymentDateFilterType = PaymentDateFilterType.convert(options.paymentDateFilterType)

        if (paymentDateFilterType?.isPaymentDate()) {
            paymentListFilter.paymentDateStart = options.startDate
            paymentListFilter.paymentDateFinish = options.finishDate
        } else if (paymentDateFilterType?.isConfirmedDate()) {
            paymentListFilter."confirmedDate[ge]" = options.startDate
            paymentListFilter."confirmedDate[le]" = options.finishDate
        } else if (paymentDateFilterType?.isCreatedDate()) {
            paymentListFilter.dateCreatedStart = options.startDate
            paymentListFilter.dateCreatedFinish = options.finishDate
        } else {
            paymentListFilter.dueDateStart = options.startDate
            paymentListFilter.dueDateFinish = options.finishDate
        }

        if (options.dueDateStart) paymentListFilter.dueDateStart = CustomDateUtils.fromDate(options.dueDateStart)
        if (options.dueDateFinish) paymentListFilter.dueDateFinish = CustomDateUtils.fromDate(options.dueDateFinish)

        if (options.notAnticipated) paymentListFilter.anticipated = false

        if (options.ignoreDunning) paymentListFilter.withoutValidDunning = true

        if (options.paymentStatusList) paymentListFilter.status = options.paymentStatusList

        return paymentListFilter
    }
}
