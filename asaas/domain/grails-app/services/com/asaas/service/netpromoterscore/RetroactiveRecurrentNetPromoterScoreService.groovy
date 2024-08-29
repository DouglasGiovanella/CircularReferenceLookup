package com.asaas.service.netpromoterscore

import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.netpromoterscore.NetPromoterScoreType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class RetroactiveRecurrentNetPromoterScoreService {

    def netPromoterScoreService

    def sessionFactory

    public Boolean processRetroactive() {
        List<Long> customerIdList = listCustomerToCreateRecurrent()
        if (!customerIdList) return false

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                netPromoterScoreService.save(Customer.load(customerId), false, NetPromoterScoreType.ASAAS)
            }, [logErrorMessage: "RetroactiveRecurrentNetPromoterScoreService.processRetroactive >> Falha ao criar NPS recorrente retroativo para o cliente [${customerId}]"])
        })

        return true
    }

    private List<Long> listCustomerToCreateRecurrent() {
        StringBuilder sql = new StringBuilder()
        sql.append(" SELECT c.id FROM net_promoter_score nps ")
        sql.append(" INNER JOIN customer c ON (c.id = nps.customer_id) ")
        sql.append(" LEFT JOIN customer_register_status crs ON (crs.customer_id = c.id) ")
        sql.append(" LEFT JOIN customer_partner_application cpa ON (cpa.customer_id = c.id) ")
        sql.append(" LEFT JOIN net_promoter_score last_nps ON (last_nps.customer_id = nps.customer_id and last_nps.deleted = 0 and last_nps.id > nps.id) ")
        sql.append(" WHERE nps.deleted = 0 ")
        sql.append(" AND last_nps.id is null ")
        sql.append(" AND (cpa.id is null OR cpa.deleted = true) ")
        sql.append(" AND nps.reply_date < :retroactiveLimitDate ")
        sql.append(" AND (nps.ignored = 1 OR nps.score IS NOT NULL) ")
        sql.append(" AND c.deleted = 0 ")
        sql.append(" AND crs.general_approval <> :generalApprovalStatus ")
        sql.append(" AND EXISTS (SELECT 1 FROM monthly_customer_confirmed_payment_summary mccps ")
        sql.append("                WHERE mccps.customer_id = c.id ")
        sql.append("                AND mccps.deleted = 0 ")
        sql.append("                AND mccps.date >= :firstDayOfMonthThreeMonthsAgoDate) ")
        sql.append(" AND NOT EXISTS (SELECT 1 FROM net_promoter_score recent_nps ")
        sql.append("                WHERE recent_nps.customer_id = c.id ")
        sql.append("                AND recent_nps.deleted = 0 ")
        sql.append("                AND recent_nps.date_created >= :threeMonthsAgoDate) ")
        sql.append(" ORDER BY nps.id DESC ")
        sql.append(" LIMIT :max ")

        Date retroactiveLimitDate = CustomDateUtils.fromString("14/03/2024")
        Date threeMonthsAgoDate = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        Date firstDayOfMonthThreeMonthsAgoDate = CustomDateUtils.getFirstDayOfMonth(threeMonthsAgoDate.clone())
        final Integer maxItemsPerCycle = 500

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setDate("threeMonthsAgoDate", threeMonthsAgoDate)
        query.setDate("firstDayOfMonthThreeMonthsAgoDate", firstDayOfMonthThreeMonthsAgoDate)
        query.setDate("retroactiveLimitDate", retroactiveLimitDate)
        query.setString("generalApprovalStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setInteger("max", maxItemsPerCycle)

        return query.list().collect { Utils.toLong(it) }
    }
}
