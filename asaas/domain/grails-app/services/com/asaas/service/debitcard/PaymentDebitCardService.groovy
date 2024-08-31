package com.asaas.service.debitcard

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.debitcard.CapturedDebitCardTransactionVo
import com.asaas.domain.debitcard.DebitCardTransactionInfo
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.receivableanticipation.CannotCancelAnticipationPartnerAcquisitionException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.payment.PaymentFeeVO
import com.asaas.payment.PaymentStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.springframework.dao.CannotAcquireLockException

@Transactional
class PaymentDebitCardService {

	def asaasSegmentioService
    def asyncActionService
    def boletoBatchFileItemService
    def chargedFeeService
    def customerFirstConfirmedPaymentService
    def customerStageService
	def financialTransactionService
	def grailsLinkGenerator
    def invoiceService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationRequestService
    def paymentConfirmService
    def paymentCustodyService
	def paymentFeeService
	def paymentSplitService
    def pixPaymentInfoService
	def promotionalCodeService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
    def receivableAnticipationService
	def receivableUnitItemService
    def paymentRefundService
    def riskAnalysisFloatService
    def transactionReceiptService
	def grailsApplication
    def receivableAnticipationValidationService
    def receivableHubPaymentOutboxService
    def asaasErpAccountingStatementService

    public Payment confirmDebitCardCapture(CapturedDebitCardTransactionVo transactionVo) {
		Payment payment = transactionVo.payment
		PaymentStatus previousPaymentStatus = payment.status

        setPaymentAsCaptured(payment, transactionVo)
        transactionReceiptService.savePaymentConfirmed(payment)
        boletoBatchFileItemService.deleteRegistrationIfRegistered(payment)

        paymentConfirmService.executeFirstConfirmedPaymentActionsIfNecessary(payment, [:])

		trackSegmentioEvent(payment, "Service :: Payment :: Pagamento com cartão de débito confirmado")

        notificationRequestService.cancelPaymentUnsentNotifications(payment)
		notificationRequestService.save(payment.customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, payment, NotificationPriority.HIGH)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentConfirmed(payment)
        receivableHubPaymentOutboxService.savePaymentConfirmed(payment)

		customerStageService.processCashInReceived(payment.provider)

		Invoice scheduledInvoice = payment.getScheduledInvoiceForPaymentConfirmation()
		if (scheduledInvoice) invoiceService.setAsPending(scheduledInvoice)
		if (previousPaymentStatus == PaymentStatus.OVERDUE) invoiceService.applyInterestPlusFineValueIfNecessary(payment.getInvoice())

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CONFIRMED)

		return payment
	}

    public List<Long> executeConfirmedPaymentsCredit(Date creditDate) {
        final Integer numberOfThreads = 2
        final Integer maxNumberOfCustomers = 1000

        List<Long> customerIdList = Payment.confirmedPaymentToSettle(BillingType.DEBIT_CARD, creditDate, [distinct: "provider.id"]).list(max: maxNumberOfCustomers)

        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> idList ->
            processPaymentCredit(idList, creditDate)
        })

        return customerIdList
    }

    private void processPaymentCredit(List<Long> customerIdList, Date creditDate) {
        final Integer numberOfOperations = 500

        List<Long> paymentIdList = Payment.confirmedPaymentToSettle(BillingType.DEBIT_CARD, creditDate, [column: "id", "providerId[in]": customerIdList]).list(max: numberOfOperations)

        Utils.forEachWithFlushSession(paymentIdList, 50, { Long paymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.get(paymentId)

                if (!payment.isConfirmed() && !payment.status.isRefundInProgress()) return

                if (payment.creditDate != creditDate) {
                    throw new RuntimeException("A data de crédito foi alterada. [paymentId: ${paymentId}, creditDate: ${payment.creditDate}]")
                }

                if (!payment.billingType.isDebitCard()) {
                    throw new RuntimeException("O tipo da cobrança foi alterado. [paymentId: ${paymentId}, billingType: ${payment.billingType}]")
                }

                BigDecimal acquirerNetValue = DebitCardTransactionInfo.query([column: "acquirerNetValue", paymentId: paymentId]).get()
                executePaymentCredit(payment, acquirerNetValue)
            }, [onError: { Exception exception ->
                if (exception instanceof CannotCancelAnticipationPartnerAcquisitionException || exception instanceof CannotAcquireLockException) {
                    AsaasLogger.warn("PaymentDebitCardService.processPaymentCredit >> Não foi possível executar débito da cobrança devido a bloqueio do registro [paymentId: ${paymentId}].")
                    return
                }

                AsaasLogger.error("PaymentDebitCardService.processPaymentCredit >> Erro ao processar liquidação de cartão de débito para a cobrança. [paymentId: ${paymentId}].", exception)
            }])
        })
    }

    private void setPaymentAsCaptured(Payment payment, CapturedDebitCardTransactionVo transactionVo) {
        PaymentStatus previousPaymentStatus = payment.status

		payment.onReceiving = true
		payment.status = PaymentStatus.CONFIRMED
		payment.confirmedDate = new Date().clearTime()
		payment.clientPaymentDate = new Date().clearTime()
        payment.creditDate = calculateEstimatedCreditDate()
        payment.estimatedCreditDate = payment.creditDate
        payment.billingType = BillingType.DEBIT_CARD

        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_CONFIRMED)

        if (!receivableAnticipationService.paymentFeeChargedByAnticipationWhenPaymentReceiving(payment)) {
            PaymentFeeVO paymentFeeVO = PaymentFeeVO.buildForDebitCard(payment, transactionVo.brand)
            payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        }

		payment.deleted = false

		payment.save(failOnError: true)

        paymentCustodyService.saveIfNecessary(payment)

        riskAnalysisFloatService.saveRiskAnalysisRequestIfNecessary(payment)

        if (previousPaymentStatus.isOverdue()) {
            receivableAnticipationValidationService.onPaymentRestore(payment)
        } else {
            receivableAnticipationValidationService.onPaymentChange(payment)
        }

		saveDebitCardTransactionInfo(payment, transactionVo)

        if (!AsaasEnvironment.isSandbox()) asyncActionService.save(AsyncActionType.SAVE_RECEIVABLE_UNIT_ITEM, [paymentId: payment.id])

        paymentConfirmService.processPaymentDunningIfExists(payment)
        paymentSplitService.updateToAwaitingCreditIfExists(payment)
	}

	private DebitCardTransactionInfo saveDebitCardTransactionInfo(Payment payment, CapturedDebitCardTransactionVo transactionVo) {
		DebitCardTransactionInfo debitCardTransactionInfo = new DebitCardTransactionInfo()
		debitCardTransactionInfo.payment = payment
		debitCardTransactionInfo.transactionIdentifier = transactionVo.transactionIdentifier
		debitCardTransactionInfo.acquirer = transactionVo.acquirer

		debitCardTransactionInfo.save(failOnError: true)

		return debitCardTransactionInfo
	}

    private void trackSegmentioEvent(Payment payment, String event) {
		try {
			Map dataMap = [providerEmail: payment.provider.email, paymentId: payment.id, customerEmail: payment.customerAccount.email, revenue: payment.getAsaasValue(), billingType: payment.billingType.toString(), dueDate: payment.dueDate, url: grailsLinkGenerator.link(controller: 'payment', action: 'show', id: payment.id, absolute: true)]
			asaasSegmentioService.track(payment.provider.id, event, dataMap)
		} catch (Exception exception) {
            AsaasLogger.error("PaymentDebitCardService.trackSegmentioEvent >> Erro ao rastrear evento Segmentio. [paymentId: ${payment?.id}]", exception)
		}
	}

	private Date calculateEstimatedCreditDate() {
		return CustomDateUtils.todayPlusBusinessDays(grailsApplication.config.payment.debitCard.daysToCredit).getTime()
	}

    public Payment executePaymentCredit(Payment payment, BigDecimal acquirerNetValue) {
		if (payment.isReceived()) return payment

        if (!payment.isDebitCard()) throw new RuntimeException("A cobrança [${payment.getInvoiceNumber()}] não pode ser recebida pois não está confirmada via cartão de débito.")

        if (!payment.isConfirmed() && !payment.status.isRefundInProgress()) throw new RuntimeException("ConfirmDebitCardReceipt: A cobrança [${payment.getInvoiceNumber()}] não pode ser recebida pois não está confirmada ou em processo de estorno.")

        if (payment.status.isRefundInProgress()) payment.hasRefundInProgress = true

        payment.paymentDate = new Date().clearTime()
        if (!payment.creditDate) payment.creditDate = new Date().clearTime()

        DebitCardTransactionInfo debitCardTransactionInfo = DebitCardTransactionInfo.query([payment: payment]).get()

        if (debitCardTransactionInfo) {
            debitCardTransactionInfo.credited = true
            if (!debitCardTransactionInfo.creditDate) debitCardTransactionInfo.creditDate = new Date().clearTime()

            if (!debitCardTransactionInfo.acquirerNetValue) debitCardTransactionInfo.acquirerNetValue = acquirerNetValue

            debitCardTransactionInfo.save(failOnError: true)
        }

		payment.status = PaymentStatus.RECEIVED

        if (promotionalCodeService.customerHasValidFeeDiscount(payment.provider)) {
            promotionalCodeService.consumeFeeDiscountPromotionalCodeAndSetNetValue(payment)
        } else if (promotionalCodeService.hasValidFreePaymentPromotionalCode(payment.provider)) {
            promotionalCodeService.consumeFreePaymentPromotionalCode(payment)
        }

		payment.save(failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment)

		receivableUnitItemService.setAsAwaitingSettlement(payment)

        financialTransactionService.savePaymentReceived(payment, null)
		paymentSplitService.executeSplit(payment)

        notificationRequestService.cancelPaymentUnsentNotifications(payment)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        receivableHubPaymentOutboxService.savePaymentReceived(payment)

		customerStageService.processCashInReceived(payment.provider)

        chargedFeeService.savePaymentSmsNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentMessagingNotificationFeeIfNecessary(payment)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RECEIVED)

        if (payment.hasRefundInProgress) {
            paymentRefundService.refund(payment, [refundOnAcquirer: true])
        }

        paymentCustodyService.processIfNecessary(payment)
        pixPaymentInfoService.purgeIfNecessary(payment)

		return payment
	}
}
