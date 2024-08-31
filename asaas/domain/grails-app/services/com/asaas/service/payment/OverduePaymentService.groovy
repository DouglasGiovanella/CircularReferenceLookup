package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.subscription.Subscription
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.payment.PaymentStatus
import com.asaas.payment.PaymentUtils
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.split.PaymentSplitCancellationReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException

@Transactional
class OverduePaymentService {

	def boletoBatchFileItemService
	def customerInvoiceService
    def debtRecoveryNegotiationPaymentService
    def notificationDispatcherPaymentNotificationOutboxService
	def notificationRequestService
    def paymentCampaignService
    def paymentOverdueErrorService
	def paymentSplitService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
	def receivableAnticipationService
	def subscriptionService
    def receivableAnticipationValidationService
    def receivableAnticipationBlocklistService
    def asaasErpAccountingStatementService

    public List<Long> processOverdueBoletoPayments(Long boletoBankId) {
        return processOverduePayments(boletoBankId, null)
    }

    public List<Long> processOverdueCreditCardPayments() {
        return processOverduePayments(null, BillingType.MUNDIPAGG_CIELO)
    }

    public List<Long> processOverduePixPayments() {
        return processOverduePayments(null, BillingType.PIX)
    }

    public void processPayment(Long paymentId) {
        String overdueProcessException
        Payment.withNewTransaction { status ->

            try {
                Payment payment = Payment.get(paymentId)

                if (payment.deleted) {
                    AsaasLogger.info("Ignoring Payment [${payment.id}] because it is deleted")
                    return
                }

                if (payment.isPaid() || payment.isReceivingProcessInitiated()) {
                    AsaasLogger.info("Ignoring Payment [${payment.id}] because it is ${payment.status}")
                    return
                }

                if (!PaymentUtils.paymentDateHasBeenExceeded(payment)) {
                    AsaasLogger.info("Ignoring Payment [${payment.id}] because payment date hasnt been exceeded")
                    return
                }

                payment.status = PaymentStatus.OVERDUE
                payment.automaticRoutine = true

                AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
                    payment.save(flush: true, failOnError: true)
                })

                receivableAnticipationValidationService.onPaymentChange(payment)

                if (payment.isPlanPayment()) {
                    Subscription planSubscription = payment.getSubscription()
                    if (planSubscription?.getPayments().findAll { it.deleted == false && (it.isConfirmed() || it.isReceived()) }.size() == 0) {
                        AsaasLogger.info("Payment [${payment.id}] - Removing Subscription [${planSubscription.id}] of new Plan [${planSubscription.plan.id}].")
                        subscriptionService.delete(planSubscription.id, payment.provider.id, true)
                        payment.customerAccount.customer.plan = null
                        return
                    }
                }

                if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && payment.cannotBePaidAnymore()) {
                    boletoBatchFileItemService.delete(payment)
                }

                paymentSplitService.refundSplit(payment, PaymentSplitCancellationReason.PAYMENT_OVERDUE)
                receivableAnticipationService.debit(payment, false)
                receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_OVERDUE)

                customerInvoiceService.processScheduleInvoiceIfNecessary(payment)

                notificationRequestService.save(payment.customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, payment, NotificationPriority.MEDIUM)

                notificationRequestService.createPaymentOverdueNotificationRequestInDailySchedule(payment)

                asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentOverdue(payment)

                debtRecoveryNegotiationPaymentService.setAsOverdueIfNecessary(payment)

                receivableAnticipationBlocklistService.savePaymentOverdueIfNecessary(payment.customerAccount, payment.billingType)

                paymentCampaignService.processOverduePayment(payment)

                paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_OVERDUE)
            } catch (HibernateOptimisticLockingFailureException holfe) {
                AsaasLogger.warn("OverduePaymentService.processPayment - ${paymentId} ", holfe)
                status.setRollbackOnly()
            } catch (Exception e) {
                overdueProcessException = "Erro: ${e.getClass().getSimpleName()} - ${e.getMessage()}"
                AsaasLogger.error("Erro ao processar cobrança vencida id: ${paymentId}. ", e)
                status.setRollbackOnly()
            }
        }

        if (overdueProcessException) saveOverdueError(paymentId, overdueProcessException)
    }

    private List<Long> listOverduePayments(Long boletoBankId, BillingType billingType, Integer maxItemCount) {
        final Integer businessDayToSubtract = 2

        Date toleranceDate = PaymentUtils.getToleranceDate()
        Date startDate = CustomDateUtils.subtractBusinessDays(toleranceDate, businessDayToSubtract)

        List<Date> dueDateList = startDate..toleranceDate

        String hql = "select p.id, p.dueDate from Payment p where p.dueDate in :dueDateList and p.status = :status and p.deleted = false"
        Map queryParams = [dueDateList: dueDateList, status: PaymentStatus.PENDING]

        if (billingType) {
            hql += " and p.billingType = :billingType"
            queryParams << [billingType: billingType]
        } else if (boletoBankId) {
            hql += " and p.boletoBank.id = :boletoBankId"
            queryParams << [boletoBankId: boletoBankId]
        } else {
            hql += " and p.boletoBank is null"
        }

        hql += " and not exists (select 1 from PaymentOverdueError poe where poe.payment = p and poe.deleted = false)"

        hql += " and not exists (select 1 from PaymentConfirmRequest pcr where pcr.payment = p and pcr.deleted = false)"

        List overduePaymentList = Payment.executeQuery(hql, queryParams, [max: maxItemCount])
        List<Long> overduePaymentIdList = overduePaymentList.collect { it[0] }

        checkForDataDifferenceInOptimizedQueryPeriod(overduePaymentList, toleranceDate)

        return overduePaymentIdList
    }

    private void checkForDataDifferenceInOptimizedQueryPeriod(List<Map> overduePaymentList, Date toleranceDate) {
        Date initialDate = buildInitialDateFromToleranceDate(toleranceDate)
        List overduePaymentDiffList = overduePaymentList.findAll { it[1] < initialDate }

        if (!overduePaymentDiffList) return

        List<Long> overduePaymentIdDiffList = overduePaymentDiffList.collect { it[0] }
        AsaasLogger.warn("OverduePaymentService.checkForDataDifferenceInOptimizedQueryPeriod >> Encontradas inconsistências na apuração de cobranças vencidas. Total: ${overduePaymentIdDiffList.size()} > ${overduePaymentIdDiffList}")
    }

    private Date buildInitialDateFromToleranceDate(Date toleranceDate) {
        Calendar initialDate = CustomDateUtils.getInstanceOfCalendar(toleranceDate)
        Calendar tempInitialDate = initialDate.clone()
        tempInitialDate.add(Calendar.DAY_OF_MONTH, -1)

        while (Holiday.isHoliday(tempInitialDate.getTime())) {
            initialDate.add(Calendar.DAY_OF_MONTH, -1)
            tempInitialDate.add(Calendar.DAY_OF_MONTH, -1)
        }

        return initialDate.getTime()
    }

    private void processOverduePaymentList(List<Long> paymentIdList) {
        Utils.withNewTransactionAndRollbackOnError({
            receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(PaymentNonAnticipableReason.PAYMENT_STATUS_OVERDUE))
        }, [ignoreStackTrace: true,
            onError: { Exception exception ->
            if (!Utils.isLock(exception)) AsaasLogger.error("OverduePaymentService.processOverduePaymentList() >>> Erro ao processar paymentIdList [${paymentIdList}].", exception)
        }])

        Utils.forEachWithFlushSession(paymentIdList, 100, { Long paymentId ->
            processPayment(paymentId)
        })
    }


    private List<Long> processOverduePayments(Long boletoBankId, BillingType billingType) {
        final Integer maxItemCount = 4000

        List<Long> overduePaymentsId = listOverduePayments(boletoBankId, billingType, maxItemCount)

        if (!overduePaymentsId) return []

        final Integer minItemsPerThread = 250
        ThreadUtils.processWithThreadsOnDemand(overduePaymentsId, minItemsPerThread, { List<Long> paymentIdList ->
            processOverduePaymentList(paymentIdList)
        })
        return overduePaymentsId
    }

    private void saveOverdueError(Long paymentId, String overdueProcessException) {
        Utils.withNewTransactionAndRollbackOnError({
            Payment payment = Payment.read(paymentId)
            paymentOverdueErrorService.save(payment, overdueProcessException)
        }, [logErrorMessage: "Erro ao registrar paymentOverdueError para paymentId: ${paymentId}"])
    }
}
