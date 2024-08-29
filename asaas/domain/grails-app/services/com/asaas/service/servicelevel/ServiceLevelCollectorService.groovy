package com.asaas.service.servicelevel

import com.asaas.billinginfo.BillingType
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.boleto.batchfile.BoletoBatchFileStatus
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.email.AsaasMailMessage
import com.asaas.domain.notification.NotificationRequest
import com.asaas.domain.payment.Payment
import com.asaas.domain.pushnotification.PushNotificationRequestPixEvent
import com.asaas.domain.servicelevel.ServiceLevel
import com.asaas.domain.sms.SmsMessage
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationStatus
import com.asaas.notification.NotificationType
import com.asaas.payment.PaymentStatus
import com.asaas.servicelevel.ServiceLevelMetric
import com.asaas.status.Status
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ServiceLevelCollectorService {

    private static final Long WHATSAPP_NOTIFICATION_TARGET = 30
    private static final Long EMAIL_NOTIFICATION_TARGET = 30
    private static final Long SMS_NOTIFICATION_TARGET = 30
    private static final Long BOLETO_REGISTRATION_TARGET = 30
    private static final Long BOLETO_REGISTRATION_UPDATE_TARGET = 90
    private static final Long BOLETO_CONFIRMATION_TARGET = 3600
    private static final Long PIX_PUSH_NOTIFICATION_FIRST_PROCESSING_TARGET = 3
    private static final Long PIX_PUSH_NOTIFICATION_SENT_TARGET = 6

    def sessionFactory

    public void collectYesterdayServiceLevels() {
        Date beginDate = CustomDateUtils.getYesterday()
        Date endDate = new Date().clearTime()

        collectWhatsappNotificationLatency(beginDate, endDate)
        collectEmailNotificationLatency(beginDate, endDate)
        collectSmsNotificationLatency(beginDate, endDate)
        collectAsaasBoletoRegistrationLatency(beginDate)
        collectBoletoRegistrationLatency(beginDate, endDate, Payment.BB_BOLETO_BANK_ID, ServiceLevelMetric.BOLETO_BB_REGISTRATION)
        collectBoletoRegistrationLatency(beginDate, endDate, Payment.BRADESCO_ONLINE_BOLETO_BANK_ID, ServiceLevelMetric.BOLETO_BRADESCO_REGISTRATION)
        collectBoletoRegistrationLatency(beginDate, endDate, Payment.SANTANDER_ONLINE_BOLETO_BANK_ID, ServiceLevelMetric.BOLETO_SANTANDER_REGISTRATION)
        collectBoletoConfirmationLatency(beginDate, endDate)
        collectPixPushNotificationFirstProcessingLatency(beginDate, endDate)
        collectPixPushNotificationSentLatency(beginDate, endDate)
    }

    private void collectWhatsappNotificationLatency(Date beginDate, Date endDate) {
        Map search = [:]
        search.columnList = ["dateCreated", "lastUpdated"]
        search."lastUpdated[ge]" = beginDate
        search."lastUpdated[lt]" = endDate
        search.status = NotificationStatus.SENT
        search.type = NotificationType.WHATSAPP
        search.priority = NotificationPriority.HIGH.priorityInt

        List<Map> createdAndSentDateList = NotificationRequest.query(search).list()

        if (!createdAndSentDateList) return

        List<Long> latencies = createdAndSentDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["dateCreated"], it["lastUpdated"])
        }

        buildAndSaveServiceLevel(ServiceLevelMetric.WHATSAPP_NOTIFICATION, beginDate, endDate, latencies, WHATSAPP_NOTIFICATION_TARGET)
    }

    private void collectEmailNotificationLatency(Date beginDate, Date endDate) {
        Map search = [:]
        search.columnList = ["notificationRequest.dateCreated", "lastUpdated"]
        search."lastUpdated[ge]" = beginDate
        search."lastUpdated[lt]" = endDate
        search."notificationRequest.status" = NotificationStatus.SENT
        search."notificationRequest.type" = NotificationType.EMAIL
        search."notificationRequest.priority" = NotificationPriority.HIGH.priorityInt

        List<Map> createdAndSentDateList = AsaasMailMessage.query(search).list()

        if (!createdAndSentDateList) return

        List<Long> latencies = createdAndSentDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["notificationRequest.dateCreated"], it["lastUpdated"])
        }

        buildAndSaveServiceLevel(ServiceLevelMetric.EMAIL_NOTIFICATION, beginDate, endDate, latencies, EMAIL_NOTIFICATION_TARGET)
    }

    private void collectSmsNotificationLatency(Date beginDate, Date endDate) {
        Map search = [:]
        search.columnList = ["notificationRequest.dateCreated", "lastUpdated"]
        search."dateCreated[ge]" = beginDate
        search."dateCreated[lt]" = endDate
        search."notificationRequest.status" = NotificationStatus.SENT
        search."notificationRequest.type" = NotificationType.SMS
        search."notificationRequest.priority" = NotificationPriority.HIGH.priorityInt

        List<Map> createdAndSentDateList = SmsMessage.query(search).list()

        if (!createdAndSentDateList) return

        List<Long> latencies = createdAndSentDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["notificationRequest.dateCreated"], it["lastUpdated"])
        }

        buildAndSaveServiceLevel(ServiceLevelMetric.SMS_NOTIFICATION, beginDate, endDate, latencies, SMS_NOTIFICATION_TARGET)
    }

    private void collectBoletoRegistrationLatency(Date beginDate, Date endDate, Long boletoBankId, ServiceLevelMetric metric) {
        collectBoletoLatency(beginDate, endDate, boletoBankId, metric, BoletoAction.CREATE, BOLETO_REGISTRATION_TARGET)
    }

    private void collectAsaasBoletoRegistrationLatency(Date baseDate) {
        Long boletoBankId = Payment.ASAAS_ONLINE_BOLETO_BANK_ID

        Date asaasBankSlipSloBeginDate = CustomDateUtils.setTime(baseDate, 0, 10, 0)
        Date asaasBankSlipSloEndDate = CustomDateUtils.setTime(baseDate, 5, 25, 0)
        collectBoletoLatency(asaasBankSlipSloBeginDate, asaasBankSlipSloEndDate, boletoBankId, ServiceLevelMetric.BOLETO_ASAAS_REGISTRATION, BoletoAction.CREATE, BOLETO_REGISTRATION_TARGET)
        collectBoletoLatency(asaasBankSlipSloBeginDate, asaasBankSlipSloEndDate, boletoBankId, ServiceLevelMetric.BOLETO_ASAAS_UPDATE, BoletoAction.UPDATE, BOLETO_REGISTRATION_UPDATE_TARGET)

        asaasBankSlipSloBeginDate = CustomDateUtils.setTime(baseDate, 6, 15, 0)
        asaasBankSlipSloEndDate = CustomDateUtils.setTime(baseDate, 23, 50, 0)
        collectBoletoLatency(asaasBankSlipSloBeginDate, asaasBankSlipSloEndDate, boletoBankId, ServiceLevelMetric.BOLETO_ASAAS_REGISTRATION, BoletoAction.CREATE, BOLETO_REGISTRATION_TARGET)
        collectBoletoLatency(asaasBankSlipSloBeginDate, asaasBankSlipSloEndDate, boletoBankId, ServiceLevelMetric.BOLETO_ASAAS_UPDATE, BoletoAction.UPDATE, BOLETO_REGISTRATION_UPDATE_TARGET)
    }

    private void collectBoletoLatency(Date beginDate, Date endDate, Long boletoBankId, ServiceLevelMetric metric, BoletoAction action, Long target) {
        Map search = [:]
        search.columnList = ["dateCreated", "lastUpdated"]
        search."dateCreated[ge]" = beginDate
        search."dateCreated[lt]" = endDate
        search."lastUpdated[ge]" = beginDate
        search."lastUpdated[lt]" = endDate
        search.status = BoletoBatchFileStatus.SENT
        search.boletoBankId = boletoBankId
        search.action = action

        List<Map> createdAndSentDateList = BoletoBatchFileItem.query(search).list()

        if (!createdAndSentDateList) return

        List<Long> latencies = createdAndSentDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["dateCreated"], it["lastUpdated"])
        }

        buildAndSaveServiceLevel(metric, beginDate, endDate, latencies, target)
    }

    private void collectBoletoConfirmationLatency(Date beginDate, Date endDate) {
        StringBuilder sql = new StringBuilder()

        sql.append("select timestampdiff(SECOND, pcr.date_created, pcr.last_updated) as timestampdiff ")
        sql.append("  from payment_confirm_request pcr ")
        sql.append("    join logs.audit_log_compressed ac ")
        sql.append("        on ac.class_name = 'Payment' ")
        sql.append("            and ac.persisted_object_id = concat('', pcr.payment_id) ")
        sql.append("            and ac.property_name       = 'status' ")
        sql.append("            and ac.new_value           = :paymentStatus ")
        sql.append("            and ac.actor               = 'system' ")
        sql.append("            and date(ac.date_created)  = date(pcr.date_created) ")
        sql.append(" where pcr.last_updated      >= :beginDate ")
        sql.append("   and pcr.date_created       < :endDate ")
        sql.append("   and pcr.status             = :pcrStatus ")
        sql.append("   and pcr.billing_type       = :pcrBillingType ")
        sql.append("   and pcr.duplicated_payment = false ")
        sql.append("   and pcr.paid_internally   is null ")
        sql.append("   and pcr.boleto_bank_id    is not null ")
        sql.append("   and pcr.deleted            = false ")
        sql.append("   and pcr.payment_id        is not null ")

        def query = sessionFactory.currentSession.createSQLQuery(sql.toString())
        query.setString("paymentStatus", PaymentStatus.RECEIVED.toString())
        query.setDate("beginDate", beginDate)
        query.setDate("endDate", endDate)
        query.setString("pcrStatus", Status.SUCCESS.toString())
        query.setString("pcrBillingType", BillingType.BOLETO.toString())

        List<Long> latencies = query.list()

        if (!latencies) return

        buildAndSaveServiceLevel(ServiceLevelMetric.BOLETO_CONFIRMATION, beginDate, endDate, latencies, BOLETO_CONFIRMATION_TARGET)
    }

    private void collectPixPushNotificationFirstProcessingLatency(Date beginDate, Date endDate) {
        Map search = [:]
        search.columnList = ["dateCreated", "firstAttemptDate"]
        search."dateCreated[ge]" = beginDate
        search."dateCreated[lt]" = endDate
        search.disableSort = true

        List<Map> createdAndFirstAttemptDateList = PushNotificationRequestPixEvent.query(search).list()
        if (!createdAndFirstAttemptDateList) return

        List<Long> latencies = createdAndFirstAttemptDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["dateCreated"], it["firstAttemptDate"])
        }

        buildAndSaveServiceLevel(ServiceLevelMetric.PIX_PUSH_NOTIFICATION_FIRST_PROCESSING, beginDate, endDate, latencies, PIX_PUSH_NOTIFICATION_FIRST_PROCESSING_TARGET)
    }

    private void collectPixPushNotificationSentLatency(Date beginDate, Date endDate) {
        Map search = [:]
        search.columnList = ["dateCreated", "lastUpdated"]
        search."dateCreated[ge]" = beginDate
        search."dateCreated[lt]" = endDate
        search.sent = true
        search.disableSort = true

        List<Map> createdAndSentDateList = PushNotificationRequestPixEvent.query(search).list()
        if (!createdAndSentDateList) return

        List<Long> latencies = createdAndSentDateList.collect {
            CustomDateUtils.calculateDifferenceInSeconds(it["dateCreated"], it["lastUpdated"])
        }

        buildAndSaveServiceLevel(ServiceLevelMetric.PIX_PUSH_NOTIFICATION_SENT, beginDate, endDate, latencies, PIX_PUSH_NOTIFICATION_SENT_TARGET)
    }

    private void buildAndSaveServiceLevel(ServiceLevelMetric metric, Date beginDate, Date endDate, List<Long> latencies, Long target) {
        Long p90 = BigDecimalUtils.percentile(latencies, 90)
        Long p95 = BigDecimalUtils.percentile(latencies, 95)
        Long p99 = BigDecimalUtils.percentile(latencies, 99)
        BigDecimal indicator = calculateIndicator(latencies, target)

        ServiceLevel serviceLevel = new ServiceLevel()
        serviceLevel.beginDate = beginDate
        serviceLevel.endDate = endDate
        serviceLevel.p90 = p90
        serviceLevel.p95 = p95
        serviceLevel.p99 = p99
        serviceLevel.metric = metric
        serviceLevel.indicator = indicator
        serviceLevel.target = target

        serviceLevel.save(failOnError: true)
    }

    private BigDecimal calculateIndicator(List<Long> latencies, Long target) {
        Long latenciesOnTarget = latencies.count { it <= target }
        BigDecimal slo = latenciesOnTarget / latencies.size() * 100
        return BigDecimalUtils.roundHalfUp(slo, 2)
    }

}
