package com.asaas.service.netpromoterscore

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.abtest.AbTestVariantChoice
import com.asaas.domain.customer.Customer
import com.asaas.domain.netpromoterscore.NetPromoterScore
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.netpromoterscore.NetPromoterScoreOrigin
import com.asaas.netpromoterscore.NetPromoterScoreType
import com.asaas.netpromoterscore.adapter.NetPromoterScoreAdapter
import com.asaas.notification.NotificationStatus
import com.asaas.notification.NotificationType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class NetPromoterScoreService {

    def abTestService
    def grailsApplication
    def messageService
    def sessionFactory

    @Deprecated
    public NetPromoterScore update(Customer customer, Long id, Integer score, String observations, NetPromoterScoreOrigin origin) {
        NetPromoterScore netPromoterScore = NetPromoterScore.find(id, customer)
        netPromoterScore.origin = origin
        netPromoterScore.ignored = false
        netPromoterScore.score = score
        netPromoterScore.observations = observations
        netPromoterScore.replyDate = new Date()

        netPromoterScore.save(failOnError: true)

        if (netPromoterScore.shouldNotifyLowScoreOrRelevantObservations()) {
            messageService.notifyAsaasTeamAboutLowScoreOrRelevantObservations(netPromoterScore)
        }

        return netPromoterScore
    }

    public NetPromoterScore reply(NetPromoterScoreAdapter netPromoterScoreAdapter) {
        if (netPromoterScoreAdapter.score == null) throw new BusinessException(Utils.getMessageProperty("netPromoterScore.reply.score.notNull"))

        NetPromoterScore netPromoterScore = NetPromoterScore.find(netPromoterScoreAdapter.id, netPromoterScoreAdapter.customer)
        netPromoterScore.origin = netPromoterScoreAdapter.origin
        netPromoterScore.ignored = false
        netPromoterScore.score = netPromoterScoreAdapter.score
        netPromoterScore.observations = netPromoterScoreAdapter.observations
        netPromoterScore.replyDate = new Date()

        netPromoterScore.save(failOnError: true)

        if (netPromoterScore.shouldNotifyLowScoreOrRelevantObservations()) messageService.notifyAsaasTeamAboutLowScoreOrRelevantObservations(netPromoterScore)

        return netPromoterScore
    }

    public void ignore(Customer customer, Long id, NetPromoterScoreOrigin origin) {
        NetPromoterScore netPromoterScore = NetPromoterScore.find(id, customer)
        if (netPromoterScore.score != null) return

        netPromoterScore.origin = origin
        netPromoterScore.ignored = true
        netPromoterScore.replyDate = new Date()

        netPromoterScore.save(failOnError: true)
    }

    public Boolean createFirst() {
        List<Long> customerIdList = listCustomersToCreateFirst()
        if (!customerIdList) return false

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                save(Customer.load(customerId), true, NetPromoterScoreType.ASAAS)
            }, [logErrorMessage: "NetPromoterScoreService.createFirst >> Falha ao criar primeiro NPS para o cliente [${customerId}]"])
        })

        return true
    }

    public Boolean createRecurrent() {
        List<Long> customerIdList = listCustomerToCreateRecurrent()
        if (!customerIdList) return false

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                save(Customer.load(customerId), false, NetPromoterScoreType.ASAAS)
            }, [logErrorMessage: "NetPromoterScoreService.createRecurrent >> Falha ao criar NPS recorrente para o cliente [${customerId}]"])
        })

        return true
    }

    public Map buildHoursSavedInfo(Customer customer) {
        Map hoursSavedInfo = [:]

        hoursSavedInfo.emailsCount = NotificationRequest.emailSent(customer: customer).count()
        hoursSavedInfo.smsCount = NotificationRequest.smsSent(customer: customer).count()
        Map notificationRequestSearch = [customer: customer, status: NotificationStatus.SENT]
        hoursSavedInfo.whatsappNotificationsCount = NotificationRequest.query(notificationRequestSearch + [type: NotificationType.WHATSAPP]).count()
        hoursSavedInfo.phoneCallNotificationsCount = NotificationRequest.query(notificationRequestSearch + [type: NotificationType.PHONE_CALL]).count()

        hoursSavedInfo.bankSlipSentByPostalServiceCount = Payment.sentByPostalService(customer: customer).count()
        hoursSavedInfo.receivedOrConfirmedPaymentsCount = Payment.receivedOrConfirmed(customer: customer).count()
        hoursSavedInfo.hoursSaved = ((hoursSavedInfo.emailsCount + hoursSavedInfo.smsCount + hoursSavedInfo.bankSlipSentByPostalServiceCount) * 3) / 60 as Integer

        if (hoursSavedInfo.hoursSaved == 0) hoursSavedInfo.hoursSaved = 1

        return hoursSavedInfo
    }

    public Boolean canShowReferral(NetPromoterScore netPromoterScore) {
        if (netPromoterScore.type.isBradescoPartner()) return false

        Integer minScoreForReferral = 7

        if (netPromoterScore.origin.isWeb()) {
            String abTestName = grailsApplication.config.asaas.abtests.npsAtlasModal.name
            String variant = AbTestVariantChoice.findVariant(abTestName, netPromoterScore.customer, AbTestPlatform.WEB)
            Boolean isVariantB = variant == grailsApplication.config.asaas.abtests.variantB
            if (isVariantB) minScoreForReferral = 8
        }

        return netPromoterScore.score >= minScoreForReferral
    }

    public Boolean drawModalAbTestAndVerifyIfIsVariantB(NetPromoterScore netPromoterScore) {
        if (!netPromoterScore) return false
        if (netPromoterScore.type.isBradescoPartner()) return false

        String variant = findOrChooseModalVariant(netPromoterScore)

        return variant == grailsApplication.config.asaas.abtests.variantB
    }

    public NetPromoterScore save(Customer customer, Boolean isFirst, NetPromoterScoreType type) {
        NetPromoterScore netPromoterScore = new NetPromoterScore()
        netPromoterScore.customer = customer
        netPromoterScore.isFirst = isFirst
        netPromoterScore.type = type
        netPromoterScore.save(failOnError: true)

        return netPromoterScore
    }

    private String findOrChooseModalVariant(NetPromoterScore netPromoterScore) {
        String abTestName = grailsApplication.config.asaas.abtests.npsAtlasModal.name
        Customer customer = netPromoterScore.customer
        AbTestPlatform abTestPlatform = AbTestPlatform.WEB

        if (!netPromoterScore.isFirst) return AbTestVariantChoice.findVariant(abTestName, customer, abTestPlatform)

        return abTestService.chooseVariant(abTestName, customer, abTestPlatform)
    }

    private List<Long> listCustomersToCreateFirst() {
        StringBuilder sql = new StringBuilder()
        sql.append("SELECT mccps.customer_id FROM monthly_customer_confirmed_payment_summary mccps ")
        sql.append(" INNER JOIN customer_register_status crs on (mccps.customer_id = crs.customer_id) ")
        sql.append(" LEFT JOIN customer_partner_application cpa ON (cpa.customer_id = mccps.customer_id) ")
        sql.append(" WHERE mccps.deleted = 0 ")
        sql.append(" AND crs.general_approval <> :generalApprovalStatus ")
        sql.append(" AND mccps.first_credit_date >= :threeMonthsAndOneWeekAgo ")
        sql.append(" AND mccps.first_credit_date <= :threeMonthsAgoDate ")
        sql.append(" AND NOT EXISTS (SELECT 1 FROM net_promoter_score nps WHERE nps.customer_id = mccps.customer_id AND nps.deleted = 0) ")
        sql.append(" AND (cpa.id is null OR cpa.deleted = true) ")
        sql.append(" LIMIT :max ")

        final Integer maxItemsPerCycle = 1000
        Date threeMonthsAgoDate = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        Date threeMonthsAndOneWeekAgo = CustomDateUtils.sumDays(threeMonthsAgoDate.clone(), -7)

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setDate("threeMonthsAgoDate", threeMonthsAgoDate)
        query.setDate("threeMonthsAndOneWeekAgo", threeMonthsAndOneWeekAgo)
        query.setString("generalApprovalStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setInteger("max", maxItemsPerCycle)

        return query.list().collect { Utils.toLong(it) }
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
        sql.append(" AND nps.reply_date >= :threeMonthsAndOneDayAgo ")
        sql.append(" AND nps.reply_date < :threeMonthsAgoDate ")
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
        sql.append(" LIMIT :max ")

        Date threeMonthsAgoDate = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        Date threeMonthsAndOneDayAgo = CustomDateUtils.sumDays(threeMonthsAgoDate.clone(), -1)
        Date firstDayOfMonthThreeMonthsAgoDate = CustomDateUtils.getFirstDayOfMonth(threeMonthsAgoDate.clone())
        final Integer maxItemsPerCycle = 1000

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setDate("threeMonthsAgoDate", threeMonthsAgoDate)
        query.setDate("threeMonthsAndOneDayAgo", threeMonthsAndOneDayAgo)
        query.setDate("firstDayOfMonthThreeMonthsAgoDate", firstDayOfMonthThreeMonthsAgoDate)
        query.setString("generalApprovalStatus", GeneralApprovalStatus.REJECTED.toString())
        query.setInteger("max", maxItemsPerCycle)

        return query.list().collect { Utils.toLong(it) }
    }
}
