package com.asaas.service.payment.paymentafterconfirmevent

import com.asaas.domain.customer.Customer
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentAfterConfirmEvent
import com.asaas.log.AsaasLogger
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventStatus
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentAfterConfirmEventProcessingService {

    def asaasErpAccountingStatementService
    def bankSlipPayerInfoAsyncActionService
    def boletoBatchFileItemService
    def customerStageService
    def invoiceService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentAfterConfirmEventService
    def paymentCampaignService
    def paymentConfirmService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def paymentSplitService
    def pixPaymentInfoService
    def receivableAnticipationBlocklistService
    def receivableAnticipationService
    def timelineEventService

    public void processPaymentEventQueue() {
        final Integer maxNumberOfOperations = 1600
        final Integer minItemsPerThread = 100
        final Integer flushEvery = 50

        List<Long> eventDataList = PaymentAfterConfirmEvent.pending([
            column: "id",
            type: PaymentAfterConfirmEventType.PAYMENT,
            order: "asc"
        ]).list(max: maxNumberOfOperations)

        ThreadUtils.processWithThreadsOnDemand(eventDataList, minItemsPerThread, { List<Long> items ->
            Utils.forEachWithFlushSession(items, flushEvery, { Long eventId ->
                processPaymentEvent(eventId)
            })
        })
    }

    public void processCustomerEventQueue() {
        final Integer maxNumberOfCustomers = 1000
        final Integer minItemsPerThread = 200
        final Integer flushEvery = 100

        List<Long> customerIdList = PaymentAfterConfirmEvent.pending([
            distinct: "customer.id",
            type: PaymentAfterConfirmEventType.CUSTOMER,
            disableSort: true
        ]).list(max: maxNumberOfCustomers)

        ThreadUtils.processWithThreadsOnDemand(customerIdList, minItemsPerThread, { List<Long> threadCustomerIdList ->
            Utils.forEachWithFlushSession(threadCustomerIdList, flushEvery, { Long customerId ->
                processCustomerEventsByCustomer(customerId)
            })
        })
    }

    public void processPaymentAfterConfirmEvent(Payment payment) {
        PaymentAfterConfirmEvent event = PaymentAfterConfirmEvent.query([
            "paymentId": payment.id,
            type: PaymentAfterConfirmEventType.PAYMENT,
            status: PaymentAfterConfirmEventStatus.PENDING
        ]).get()

        if (!event) return

        executePaymentRoutines(event)
        executeCustomerRoutines(payment.provider.id)
    }

    private void processPaymentEvent(Long eventId) {
        Boolean hasError = false
        Utils.withNewTransactionAndRollbackOnError({
            PaymentAfterConfirmEvent event = PaymentAfterConfirmEvent.get(eventId)
            executePaymentRoutines(event)
        }, [ignoreStackTrace: true,
            onError: { Exception exception ->
                if (Utils.isLock(exception)) return

                AsaasLogger.error("PaymentAfterConfirmEventProcessingService.processPaymentEvent >> Erro ao processar o evento [${eventId}]", exception)
                hasError = true
            }]
        )

        if (hasError) {
            Utils.withNewTransactionAndRollbackOnError({
                paymentAfterConfirmEventService.setAsError(PaymentAfterConfirmEvent.get(eventId))
            }, [logErrorMessage: "PaymentAfterConfirmEventProcessingService.processPaymentRoutinesEvent >> Erro ao atualizar o status do evento [${eventId}] para [${PaymentAfterConfirmEventStatus.ERROR.toString()}]"])
        }
    }

    private void processCustomerEventsByCustomer(Long customerId) {
        Boolean hasError = false
        Utils.withNewTransactionAndRollbackOnError({
            executeCustomerRoutines(customerId)
        }, [ignoreStackTrace: true,
            onError: { Exception exception ->
                if (Utils.isLock(exception)) return

                AsaasLogger.error("PaymentAfterConfirmEventProcessingService.processCustomerEventsByCustomer >> Erro ao processar os eventos do cliente [${customerId}]", exception)
                hasError = true
            }]
        )

        if (hasError) {
            Utils.withNewTransactionAndRollbackOnError({
                paymentAfterConfirmEventService.setCustomerEventsAsError(customerId)
            }, [logErrorMessage: "PaymentAfterConfirmEventProcessingService.processCustomerEventsByCustomer >> Erro ao atualizar o status dos eventos do cliente [${customerId}] para [${PaymentAfterConfirmEventStatus.ERROR.toString()}]"])
        }
    }

    private void executePaymentRoutines(PaymentAfterConfirmEvent event) {
        Payment payment = event.payment
        if (payment.status != event.paymentStatusOnEventCreation) throw new RuntimeException("O status da cobrança [${event.payment.id} - ${event.payment.status.toString()}] não pode ser diferente do que estava na criação do evento [${event.paymentStatusOnEventCreation.toString()}]")

        Invoice scheduledInvoice = payment.getScheduledInvoiceForPaymentConfirmation()
        if (scheduledInvoice) invoiceService.setAsPending(scheduledInvoice)
        if (event.paymentStatusBeforeConfirmation.isOverdue()) invoiceService.applyInterestPlusFineValueIfNecessary(payment.getInvoice())

        Map additionalParameters = [:]
        if (payment.receivedValueIsDifferentFromExpected()) {
            timelineEventService.createUnexpectedValueReceivedEvent(payment)
            additionalParameters = [unexpectedValueReceived: true]
        }
        paymentConfirmService.executeFirstConfirmedPaymentActionsIfNecessary(payment, additionalParameters)
        paymentConfirmService.trackSegmentioEvent(payment, "Service :: Payment :: Pagamento Confirmado", additionalParameters)

        if (event.paymentStatusOnEventCreation.isConfirmed()) {
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CONFIRMED)
            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment, null, true)
            paymentSplitService.updateToAwaitingCreditIfExists(payment)
        }
        notificationDispatcherPaymentNotificationOutboxService.savePaymentConfirmed(payment)

        paymentCampaignService.processPaymentReceived(payment)
        boletoBatchFileItemService.deleteRegistrationIfRegistered(payment)
        receivableAnticipationBlocklistService.setOverdueBankSlipPaymentToBeReanalyzeTomorrow(payment.customerAccount)
        pixPaymentInfoService.purgeIfNecessary(payment)
        bankSlipPayerInfoAsyncActionService.saveIfNecessary(payment)

        event.type = PaymentAfterConfirmEventType.CUSTOMER
        event.save(failOnError: true)
    }

    private void executeCustomerRoutines(Long customerId) {
        Customer customer = Customer.get(customerId)

        customerStageService.processCashInReceived(customer)

        paymentAfterConfirmEventService.deleteCustomerEvents(customerId)
    }
}
