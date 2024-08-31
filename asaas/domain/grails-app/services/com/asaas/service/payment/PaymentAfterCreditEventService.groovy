package com.asaas.service.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentAfterCreditEvent
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventStatus
import com.asaas.paymentaftercreditevent.PaymentAfterCreditEventStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentAfterCreditEventService {

    def asaasErpAccountingStatementService
    def installmentService
    def notificationDispatcherPaymentNotificationOutboxService
    def receivableAnticipationBlocklistService
    def planPaymentService
    def paymentPushNotificationRequestAsyncPreProcessingService

    public void save(Payment payment) {
        PaymentAfterCreditEvent event = new PaymentAfterCreditEvent()
        event.payment = payment
        event.status = PaymentAfterCreditEventStatus.PENDING
        event.paymentStatusOnEventCreation = payment.status

        event.save(failOnError: true)
    }

    public void processPendingEventQueue() {
        final Integer minItemsPerThread = 250
        final Integer maxNumberOfEvents = 1400
        final Integer flushEvery = 50

        List<Long> eventIdList = PaymentAfterCreditEvent.pending([column: "id", order: "asc"]).list(max: maxNumberOfEvents)

        ThreadUtils.processWithThreadsOnDemand(eventIdList, minItemsPerThread, { List<Long> eventIdListPerThread ->
            Utils.forEachWithFlushSession(eventIdListPerThread, flushEvery, { Long eventId ->
                processEvent(eventId)
            })
        })
    }

    public void processPaymentAfterCreditEvent(Payment payment) {
        PaymentAfterCreditEvent event = PaymentAfterCreditEvent.query([
            "paymentId": payment.id,
            status: PaymentAfterConfirmEventStatus.PENDING
        ]).get()

        if (!event) return

        executeRoutines(event)
    }

    private void processEvent(Long eventId) {
        Boolean hasError = false
        Utils.withNewTransactionAndRollbackOnError({
            executeRoutines(PaymentAfterCreditEvent.get(eventId))
        }, [ignoreStackTrace: true,
            onError: { Exception exception ->
                if (Utils.isLock(exception)) return

                AsaasLogger.error("PaymentAfterCreditEventService.processEvent >> Erro ao processar o evento [${eventId}]", exception)
                hasError = true
            }])

        if (hasError) {
            Utils.withNewTransactionAndRollbackOnError({
                setAsError(PaymentAfterCreditEvent.get(eventId))
            }, [logErrorMessage: "PaymentAfterCreditEventService.processEvent >> Erro ao atualizar o status do evento [${eventId}] para [${PaymentAfterCreditEventStatus.ERROR.toString()}]"])
        }
    }

    private setAsError(PaymentAfterCreditEvent event) {
        event.status = PaymentAfterCreditEventStatus.ERROR
        event.save(failOnError: true)
    }

    private executeRoutines(PaymentAfterCreditEvent event) {
        Payment payment = event.payment

        List<PaymentStatus> allowedAfterCreditPaymentStatusList = [PaymentStatus.RECEIVED, PaymentStatus.DUNNING_RECEIVED]
        if (!allowedAfterCreditPaymentStatusList.contains(payment.status)) throw new RuntimeException("O status da cobrança [${event.payment.id} - ${event.payment.status.toString()}] não pode ser diferente do que estava na criação do evento [${event.paymentStatusOnEventCreation.toString()}]")

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment, null, true)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        receivableAnticipationBlocklistService.setAnticipationOverdueToBeReanalyzeToday(payment.customerAccount.cpfCnpj)
        planPaymentService.processPlanPaymentIfNecessary(payment)
        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RECEIVED)
        if (payment.installment) installmentService.notifyCustomerAboutInstallmentEnding(payment.installment)

        event.delete(failOnError: true)
    }
}
