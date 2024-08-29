package com.asaas.service.paymentinfo

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class PaymentAnticipableInfoService {

    def sessionFactory

    public void save(Payment payment) {
        if (!canExecuteSave(payment)) return

        PaymentAnticipableInfo paymentAnticipableInfo = new PaymentAnticipableInfo()
        paymentAnticipableInfo.payment = payment
        paymentAnticipableInfo.installment = Installment.load(payment.installmentId)
        paymentAnticipableInfo.customer = Customer.load(payment.providerId)
        paymentAnticipableInfo.anticipated = payment.hasValidReceivableAnticipation()
        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.schedulable = false
        paymentAnticipableInfo.billingType = payment.billingType
        paymentAnticipableInfo.value = payment.value
        paymentAnticipableInfo.creditDate = payment.creditDate
        paymentAnticipableInfo.dueDate = payment.dueDate
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void onPaymentDeleted(Long paymentId) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.delete()
    }

    public void updateIfNecessary(Payment payment) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(payment.id)
        if (!paymentAnticipableInfo) return

        if (canExecuteHardDelete(payment)) {
            paymentAnticipableInfo.delete()
            return
        }

        paymentAnticipableInfo.billingType = payment.billingType
        paymentAnticipableInfo.value = payment.value
        paymentAnticipableInfo.creditDate = payment.creditDate
        paymentAnticipableInfo.dueDate = payment.dueDate

        final List<String> fieldToWatchList = ["billingType", "value", "dueDate", "creditDate"]
        Boolean anyWatchedFieldChanged = fieldToWatchList.any {  paymentAnticipableInfo.isDirty(it) }
        if (!anyWatchedFieldChanged) return

        paymentAnticipableInfo.save(failOnError: true)
    }

    public void setAsAnticipable(Long paymentId) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.anticipable = true
        paymentAnticipableInfo.anticipated = false
        paymentAnticipableInfo.schedulable = false
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED
        paymentAnticipableInfo.nonAnticipableReason = null
        paymentAnticipableInfo.nonAnticipableDescription = null
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void setAsNotAnticipable(Long paymentId, ReceivableAnticipationNonAnticipableReasonVO reason) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED
        paymentAnticipableInfo.nonAnticipableReason = reason?.reason
        paymentAnticipableInfo.nonAnticipableDescription = reason?.message
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void setAsSchedulable(Long paymentId) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.schedulable = true
        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void setAsNotSchedulable(Long paymentId, ReceivableAnticipationNonAnticipableReasonVO reason) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.schedulable = false
        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED
        paymentAnticipableInfo.nonAnticipableReason = reason?.reason
        paymentAnticipableInfo.nonAnticipableDescription = reason?.message
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void setAsAnticipated(Long paymentId, Boolean anticipationIsScheduled) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.schedulable = false
        paymentAnticipableInfo.anticipated = true
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.ANALYZED

        ReceivableAnticipationNonAnticipableReasonVO receivableAnticipationNonAnticipableReason
        if (anticipationIsScheduled) {
            receivableAnticipationNonAnticipableReason = new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_HAS_SCHEDULED_ANTICIPATION)
        } else {
            receivableAnticipationNonAnticipableReason = new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_HAS_ALREADY_ANTICIPATED)
        }

        paymentAnticipableInfo.nonAnticipableReason = receivableAnticipationNonAnticipableReason.reason
        paymentAnticipableInfo.nonAnticipableDescription = receivableAnticipationNonAnticipableReason.message
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void sendToAnalysisQueue(Long paymentId) {
        PaymentAnticipableInfo paymentAnticipableInfo = PaymentAnticipableInfo.findByPaymentId(paymentId)
        if (!paymentAnticipableInfo) return

        sendToAnalysisQueue(paymentAnticipableInfo)
    }

    public void sendToAnalysisQueue(PaymentAnticipableInfo paymentAnticipableInfo) {
        paymentAnticipableInfo.anticipated = false
        paymentAnticipableInfo.anticipable = false
        paymentAnticipableInfo.status = PaymentAnticipableInfoStatus.AWAITING_ANALYSIS
        paymentAnticipableInfo.save(failOnError: true)
    }

    public void bulkSetAnticipableAndSchedulable(List<Long> paymentIdList, ReceivableAnticipationNonAnticipableReasonVO reason) {
        if (!paymentIdList) return

        String sql = """
            UPDATE payment_anticipable_info pai FORCE INDEX (FK347610642FBA69C2)
            SET pai.anticipable = :anticipable,
            pai.schedulable = :schedulable,
            pai.non_anticipable_reason = :nonAnticipableReason,
            pai.non_anticipable_description = :nonAnticipableDescription,
            pai.status = :status,
            last_updated = :lastUpdated,
            version = version + 1
            WHERE pai.anticipated = false
            AND pai.payment_id IN (:paymentIdList)
        """

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setBoolean("anticipable", reason == null)
        query.setBoolean("schedulable", reason != null)
        query.setString("nonAnticipableReason", reason?.reason?.toString())
        query.setString("nonAnticipableDescription", reason?.message?.toString())
        query.setString("status", PaymentAnticipableInfoStatus.ANALYZED.toString())
        query.setTimestamp("lastUpdated", new Date())
        query.setParameterList("paymentIdList", paymentIdList)
        query.executeUpdate()
    }

    public void bulkSetPaymentsAsNotAnticipableAndSchedulable(List<Long> paymentIdList, ReceivableAnticipationNonAnticipableReasonVO reason) {
        if (!paymentIdList) return

        PaymentAnticipableInfo.executeUpdate("""
           UPDATE PaymentAnticipableInfo
              SET anticipable = :anticipable, schedulable = :schedulable, nonAnticipableReason = :nonAnticipableReason,  nonAnticipableDescription = :nonAnticipableDescription,
              status = :status, lastUpdated = :lastUpdated, version = version + 1
            WHERE anticipated = false AND payment.id IN :paymentIdList""",
            [
                anticipable: false,
                schedulable: false,
                nonAnticipableReason: reason.reason,
                nonAnticipableDescription: reason.message,
                status: PaymentAnticipableInfoStatus.ANALYZED,
                lastUpdated: new Date(),
                paymentIdList: paymentIdList
            ])
    }

    public void bulkProcessPaymentsByReason(List<Long> paymentIdList, ReceivableAnticipationNonAnticipableReasonVO reasonVO) {
        if (reasonVO && (reasonVO.reason.isBankSlipAndPixAnticipationDisabled() || reasonVO.reason.isCreditCardAnticipationDisabled())) {
            bulkDeletePaymentsAnticipable(paymentIdList)
            return
        }

        bulkSetPaymentsAsNotAnticipableAndSchedulable(paymentIdList, reasonVO)
    }

    public void bulkSendPaymentsToAnalysisQueue(List<Long> paymentIdList) {
        PaymentAnticipableInfo.executeUpdate("""
           UPDATE PaymentAnticipableInfo
              SET anticipable = :anticipable, schedulable = :schedulable, anticipated = :anticipated, lastUpdated = :lastUpdated, version = version + 1, status = :status
            WHERE anticipated = false AND payment.id IN :paymentIdList""",
            [
                anticipable: false,
                schedulable: false,
                anticipated: false,
                lastUpdated: new Date(),
                paymentIdList: paymentIdList,
                status: PaymentAnticipableInfoStatus.AWAITING_ANALYSIS
            ])
    }

    public void updateAnticipableAsAwaitingAnalysisForAllAnticipableBillingTypes(Map search) {
        updateAnticipableAsAwaitingAnalysis(BillingType.BOLETO, search)
        updateAnticipableAsAwaitingAnalysis(BillingType.PIX, search)
        updateAnticipableAsAwaitingAnalysis(BillingType.MUNDIPAGG_CIELO, search)
    }

    public void updateAnticipableAsAwaitingAnalysis(BillingType billingType, Map search) {
        List<Long> paymentIdList = PaymentAnticipableInfo.query(search + buildDefaultFiltersForReadyToValidateAnticipable(billingType)).list()

        final Integer maxPaymentsPerUpdate = 3000
        for (List<Long> idList : paymentIdList.collate(maxPaymentsPerUpdate)) {
            bulkSendPaymentsToAnalysisQueue(idList)
        }

        if (search.containsKey("customer")) {
            CustomerStatistic.expireTotalValueAvailableForAnticipation(search.customer)
        } else {
            expireCustomerTotalValueAvailableForAnticipation(paymentIdList)
        }
    }

    public void expireCustomerTotalValueAvailableForAnticipation(List<Long> paymentIdList) {
        if (!paymentIdList) return

        List<Customer> customerList = []

        final Integer maxCollatedPayments = 2000
        paymentIdList.collate(maxCollatedPayments).each {
            List<Long> paymentIdSubList = it.collect()
            customerList.addAll(Payment.query([distinct: "provider", "id[in]": paymentIdSubList, disableSort: true]).list())
        }

        customerList = customerList.unique { it.id }

        for (Customer customer : customerList) {
            CustomerStatistic.expireTotalValueAvailableForAnticipation(customer)
        }
    }

    private Boolean canExecuteHardDelete(Payment payment) {
        if (payment.deleted) return true
        if (payment.status.isReceived()) return true
        if (payment.status.isReceivedInCash()) return true
        if (payment.status.isOverdue()) return true
    }

    private Boolean canExecuteSave(Payment payment) {
        if (ReceivableAnticipationValidationClosures.anticipationIsEnabled(payment)) return false

        return true
    }

    private void bulkDeletePaymentsAnticipable(List<Long> paymentIdList) {
        PaymentAnticipableInfo.executeUpdate("DELETE PaymentAnticipableInfo WHERE anticipated = :anticipated AND payment.id IN :paymentIdList", [anticipated: false, paymentIdList: paymentIdList])
    }

    private Map buildDefaultFiltersForReadyToValidateAnticipable(BillingType billingType) {
        Map search = [:]

        if (billingType.isCreditCard()) {
            search.paymentStatus = PaymentStatus.CONFIRMED
            search."paymentCreditDate[ge]" = ReceivableAnticipation.getMinimumDateAllowed()
        } else {
            search.paymentStatus = PaymentStatus.PENDING
            search."dueDate[ge]" = ReceivableAnticipation.getMinimumDateAllowed()
        }

        return search + [
            column: "payment.id",
            status: PaymentAnticipableInfoStatus.ANALYZED,
            anticipable: false,
            anticipated: false,
            billingType: billingType,
            disableSort: true
        ]
    }
}
