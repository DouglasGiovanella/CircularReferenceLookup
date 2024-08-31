package com.asaas.service.netpromoterscore.customerpartnerapplication

import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.netpromoterscore.NetPromoterScoreType
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class BradescoPartnerNetPromoterScoreService {

    def netPromoterScoreService
    def sessionFactory

    public List<Long> createFirst() {
        List<Long> customerIdList = listCustomersToCreateFirst()

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                netPromoterScoreService.save(customer, true, NetPromoterScoreType.BRADESCO_PARTNER)
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.createFirst >> Falha ao criar primeiro NPS para o cliente [${customerId}]"])
        })

        return customerIdList
    }

    public List<Long> createRecurrent() {
        List<Long> customerIdList = listCustomerToCreateRecurrent()
        List<Long> processedCustomerIdList = []

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                netPromoterScoreService.save(customer, false, NetPromoterScoreType.BRADESCO_PARTNER)
                processedCustomerIdList.add(customerId)
            }, [logErrorMessage: "BradescoPartnerNetPromoterScoreService.createRecurrent >> Falha ao criar NPS recorrente para o cliente [${customerId}]"])
        })

        customerIdList -= processedCustomerIdList

        return customerIdList
    }

    private List<Long> listCustomersToCreateFirst() {
        StringBuilder sql = new StringBuilder()
        sql.append(" SELECT c.id FROM customer c ")
        sql.append(" INNER JOIN customer_partner_application cpa ON cpa.customer_id = c.id ")
        sql.append(" INNER JOIN customer_register_status crs ON c.id = crs.customer_id ")
        sql.append(" LEFT JOIN net_promoter_score nps ON nps.customer_id = c.id AND nps.deleted = 0 ")
        sql.append(" WHERE c.deleted = 0 ")
        sql.append(" AND cpa.deleted = 0 ")
        sql.append(" AND nps.id IS NULL ")
        sql.append(" AND cpa.partner_application_name = :partnerApplicationName ")
        sql.append(" AND crs.general_approval <> :generalApprovalStatus ")
        sql.append(" AND EXISTS (SELECT 1 FROM payment p ")
        sql.append("                WHERE p.provider_id = c.id ")
        sql.append("                AND p.deleted = 0 ")
        sql.append("                AND p.date_created >= :startDate ")
        sql.append("                AND p.date_created < :endDate ) ")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setDate("startDate", CustomDateUtils.fromString("01/01/2023"))
        query.setDate("endDate", new Date().clearTime())
        query.setString("generalApprovalStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setString("partnerApplicationName", PartnerApplicationName.BRADESCO.toString())

        return query.list().collect { Utils.toLong(it) }
    }

    private List<Long> listCustomerToCreateRecurrent() {
        StringBuilder sql = new StringBuilder()
        sql.append(" SELECT c.id FROM customer c ")
        sql.append(" INNER JOIN customer_partner_application cpa ON cpa.customer_id = c.id ")
        sql.append(" INNER JOIN customer_register_status crs on c.id = crs.customer_id ")
        sql.append(" LEFT JOIN net_promoter_score nps ON nps.customer_id = c.id ")
        sql.append(" WHERE c.deleted = 0 ")
        sql.append(" AND cpa.deleted = 0 ")
        sql.append(" AND cpa.partner_application_name = :partnerApplicationName")
        sql.append(" AND crs.general_approval <> :generalApprovalStatus ")
        sql.append(" AND nps.reply_date < :fifteenDaysAgoDate ")
        sql.append(" AND (nps.ignored = 1 OR nps.score IS NOT NULL) ")
        sql.append(" AND EXISTS (SELECT 1 FROM payment p ")
        sql.append("                WHERE p.provider_id = c.id ")
        sql.append("                AND p.deleted = 0 ")
        sql.append("                AND p.status IN (:paymentStatusList) ")
        sql.append("                AND p.payment_date >= nps.reply_date) ")
        sql.append(" AND NOT EXISTS (SELECT 1 FROM net_promoter_score recent_nps ")
        sql.append("                WHERE recent_nps.customer_id = c.id ")
        sql.append("                AND recent_nps.deleted = 0 ")
        sql.append("                AND recent_nps.id > nps.id) ")

        Date fifteenDaysAgo = CustomDateUtils.sumDays(new Date(), -15)

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setString("generalApprovalStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setString("partnerApplicationName", PartnerApplicationName.BRADESCO.toString())
        query.setDate("fifteenDaysAgoDate", fifteenDaysAgo)
        query.setParameterList("paymentStatusList", [PaymentStatus.RECEIVED.toString(), PaymentStatus.CONFIRMED.toString()])

        return query.list().collect { Utils.toLong(it) }
    }
}
