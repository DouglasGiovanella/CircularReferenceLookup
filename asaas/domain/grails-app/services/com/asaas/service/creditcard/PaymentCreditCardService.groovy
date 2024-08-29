package com.asaas.service.creditcard

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CapturedCreditCardTransactionVo
import com.asaas.creditcard.CreditCardGateway
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentGatewayInfo
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.subscription.Subscription
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.receivableanticipation.CannotCancelAnticipationPartnerAcquisitionException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.payment.PaymentFeeVO
import com.asaas.payment.PaymentStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.springframework.dao.CannotAcquireLockException

@Transactional
class PaymentCreditCardService {

	def asaasSegmentioService
	def asyncActionService
	def boletoBatchFileItemService
    def customerFirstConfirmedPaymentService
	def customerStageService
    def doublePromotionService
	def financialTransactionService
	def invoiceService
    def installmentService
	def messageService
	def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
	def notificationRequestService
	def paymentCampaignService
	def paymentConfirmService
    def paymentCustodyService
	def paymentFeeService
	def paymentSplitService
    def pixPaymentInfoService
	def planPaymentService
	def promotionalCodeService
	def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
	def receivableAnticipationService
	def receivableUnitItemService
	def transactionReceiptService
    def asaasErpAccountingStatementService
    def asaasMoneyTransactionInfoService
    def chargedFeeService
    def creditCardTransactionAnalysisService
    def creditCardTransactionInfoService
    def paymentRefundService
    def receivableAnticipationBatchService
    def receivableAnticipationLimitRecalculationService
    def receivableAnticipationSchedulingService
    def receivableAnticipationValidationService
    def receivableHubPaymentOutboxService
	def grailsLinkGenerator

	public void saveGatewayInfo(Payment payment, CreditCardGateway creditCardGateway, String mundiPaggOrderKey, String mundiPaggTransactionKey) {
		if (payment.installment) {
            Integer installmentNumber = 0

            for (Payment paymentRemaining in payment.installment.getRemainingPayments()) {
                setGatewayInfo(paymentRemaining, creditCardGateway, mundiPaggOrderKey, mundiPaggTransactionKey, ++installmentNumber)
			}
		} else {
			setGatewayInfo(payment, creditCardGateway, mundiPaggOrderKey, mundiPaggTransactionKey, 1)
		}
	}

    public List<Long> executeConfirmedPaymentsCredit(Date creditDate) {
        if (creditDate.clone().clearTime() > new Date().clearTime()) {
            throw new RuntimeException("A data de crédito não pode ser maior que a data atual!")
        }

        final Integer numberOfThreads = 8
        final Integer maxNumberOfCustomers = 2000

        List<Long> customerIdList = Payment.confirmedPaymentToSettle(BillingType.MUNDIPAGG_CIELO, creditDate, [distinct: "provider.id"]).list(max: maxNumberOfCustomers)

        Utils.processWithThreads(customerIdList, numberOfThreads, { List<Long> idList ->
            processPaymentCredit(idList, creditDate)
        })

        return customerIdList
    }

    private void processPaymentCredit(List<Long> customerIdList, Date creditDate) {
        final Integer numberOfOperations = 250

        List<Long> paymentIdList = Payment.confirmedPaymentToSettle(BillingType.MUNDIPAGG_CIELO, creditDate, [column: "id", "providerId[in]": customerIdList]).list(max: numberOfOperations)

        Utils.forEachWithFlushSession(paymentIdList, 50, { Long paymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.get(paymentId)

                if (!payment.isConfirmed() && !payment.status.isRefundInProgress()) return

                if (payment.creditDate != creditDate) {
                    throw new RuntimeException("A data de crédito foi alterada. [paymentId: ${paymentId}, creditDate: ${payment.creditDate}]")
                }

                if (!payment.billingType.isCreditCard()) {
                    throw new RuntimeException("O tipo da cobrança foi alterado. [paymentId: ${paymentId}, billingType: ${payment.billingType}]")
                }

                executePaymentCredit(payment)
            }, [onError: { Exception exception ->
                if (exception instanceof CannotCancelAnticipationPartnerAcquisitionException || exception instanceof CannotAcquireLockException) {
                    AsaasLogger.warn("PaymentCreditCardService.processPaymentCredit >> Não foi possível executar crédito da cobrança devido a bloqueio do registro [paymentId: ${paymentId}].")
                    return
                }

                AsaasLogger.error("PaymentCreditCardService.processPaymentCredit >> Erro ao processar liquidação de cartão de crédito para a cobrança. [paymentId: ${paymentId}].", exception)
            }])
        })
    }

	private void setGatewayInfo(Payment payment, CreditCardGateway creditCardGateway, String mundiPaggOrderKey, String mundiPaggTransactionKey, Integer installmentNumber) {
        PaymentGatewayInfo paymentGatewayInfo = PaymentGatewayInfo.query(["paymentId": payment.id]).get() ?: new PaymentGatewayInfo()
        paymentGatewayInfo.payment = payment
        paymentGatewayInfo.gateway = creditCardGateway
        paymentGatewayInfo.mundiPaggOrderKey = mundiPaggOrderKey
        paymentGatewayInfo.mundiPaggTransactionKey = mundiPaggTransactionKey
        paymentGatewayInfo.installmentNumber = installmentNumber
        paymentGatewayInfo.save(failOnError: true)
	}

	private void updateBillingInfo(Payment payment, BillingInfo billingInfo) {
		if (payment.installment) {
			for (Payment p in payment.installment.getRemainingPayments()) {
				p.billingInfo = billingInfo
				p.save(failOnError: true)
			}
		} else if (payment.getSubscription()) {
			Subscription subscription = payment.subscription
			subscription.billingInfo = billingInfo
			subscription.billingType = billingInfo.billingType
			subscription.save(flush: true, failOnError: true)

			payment.billingInfo = billingInfo
			payment.save(failOnError: true)

			subscription.payments.findAll { it.isPending() }.each {
				it.billingInfo = billingInfo
				it.billingType = billingInfo.billingType
				it.save(failOnError: true)

                receivableAnticipationValidationService.onPaymentChange(it)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(it)
			}
		} else {
			payment.billingInfo = billingInfo
			payment.save(failOnError: true)
		}
	}

    public void setCreditCardTransactionAsAuthorized(CapturedCreditCardTransactionVo transactionVo, BillingInfo billingInfo) {
        Payment payment = transactionVo.payment

        updateBillingInfo(payment, billingInfo)

        if (payment.installment) {
            Integer installmentNumber = 1

            for (Payment remainingPayment : payment.installment.getRemainingPayments()) {
                remainingPayment.installmentNumber = installmentNumber
                setPaymentAsAuthorized(remainingPayment, remainingPayment.installmentNumber, transactionVo)
                paymentPushNotificationRequestAsyncPreProcessingService.save(remainingPayment, PushNotificationRequestEvent.PAYMENT_AUTHORIZED)

                installmentNumber++
            }
        } else {
            setPaymentAsAuthorized(payment, 1, transactionVo)
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_AUTHORIZED)
        }
    }

	public Payment confirmCreditCardCapture(CapturedCreditCardTransactionVo transactionVo, BillingInfo billingInfo) {
		Payment payment = transactionVo.payment

		updateBillingInfo(payment, billingInfo)

		if (payment.installment) {
			setInstallmentAsCaptured(payment.installment, transactionVo)
			transactionReceiptService.savePaymentConfirmed(payment.installment)
			boletoBatchFileItemService.deleteRegistrationIfRegistered(payment.installment)
		} else {
			setPaymentAsCaptured(payment, 1, transactionVo)
			transactionReceiptService.savePaymentConfirmed(payment)
			boletoBatchFileItemService.deleteRegistrationIfRegistered(payment)
		}

        receivableAnticipationValidationService.onPaymentChange(payment.installment ?: payment)

        Boolean anticipable = PaymentAnticipableInfo.findAnticipableByPaymentId(payment.id)
        if (anticipable) receivableAnticipationBatchService.saveSimulationBatchToAutomaticAnticipationIfNecessary(payment.provider)

        receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(payment.provider, false)

		planPaymentService.processPlanPaymentIfNecessary(payment)

        paymentConfirmService.executeFirstConfirmedPaymentActionsIfNecessary(payment, [:])

		trackSegmentioEvent(payment, "Service :: Payment :: Pagamento com cartão de crédito confirmado")

        notificationRequestService.cancelUnsentNotifications(payment, payment.installment)
		notificationRequestService.save(payment.customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, payment, NotificationPriority.HIGH)
		if (payment.automaticCreditCardCapture) notificationRequestService.cancelCustomerAccountPendingNotification(payment)

        notificationDispatcherPaymentNotificationOutboxService.savePaymentConfirmed(payment)
        receivableHubPaymentOutboxService.savePaymentConfirmed(payment)

        mobilePushNotificationService.notifyPaymentConfirmed(payment)

		customerStageService.processCashInReceived(payment.provider)

        paymentCampaignService.processPaymentCreditCardReceived(payment)

        if (payment.installment) {
            for (Payment confirmedPayment in payment.installment.getConfirmedPayments()) {
                paymentPushNotificationRequestAsyncPreProcessingService.save(confirmedPayment, PushNotificationRequestEvent.PAYMENT_CONFIRMED)

                asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, confirmedPayment)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(confirmedPayment)
            }
        } else {
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CONFIRMED)

            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        asaasMoneyTransactionInfoService.setAsPaidIfNecessary(payment)

		return payment
	}

    private void setInstallmentAsCaptured(Installment installment, CapturedCreditCardTransactionVo transactionVo) {
        installment = setInstallmentPaymentDate(installment)
        Integer installmentNumber = 1

        for (Payment remainingPayment : installment.getRemainingPayments()) {
            remainingPayment.installmentNumber = installmentNumber
            setPaymentAsCaptured(remainingPayment, remainingPayment.installmentNumber, transactionVo)
            remainingPayment.save(flush: true, failOnError: true)

            installmentNumber++
        }

        List<Payment> unconfirmedPaymentList = installment.getNotDeletedPayments().findAll { !it.isConfirmed() }.sort { it.installmentNumber }

        for (Payment unconfirmedPayment : unconfirmedPaymentList) {
            unconfirmedPayment.installmentNumber = installmentNumber
            unconfirmedPayment.save(failOnError: true)

            installmentNumber++
        }

        receivableAnticipationSchedulingService.handleInstallmentConfirmation(installment)
    }

    private void setPaymentAsAuthorized(Payment payment, Integer installmentNumber, CapturedCreditCardTransactionVo transactionVo) {
        payment.creditCardTid = transactionVo.transactionIdentifier
        payment.creditCardUsn = transactionVo.uniqueSequentialNumber
        payment.status = PaymentStatus.AUTHORIZED
        payment.billingType = BillingType.MUNDIPAGG_CIELO
        payment.deleted = false

        PaymentFeeVO paymentFeeVO = PaymentFeeVO.buildForCreditCard(payment, transactionVo.brand)
        payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)

        payment.save(failOnError: true)

        creditCardTransactionInfoService.save(payment, installmentNumber, transactionVo)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
    }

	private void setPaymentAsCaptured(Payment payment, Integer installmentNumber, CapturedCreditCardTransactionVo transactionVo) {
		PaymentStatus previousPaymentStatus = payment.status
        Date confirmedDate = transactionVo.confirmedDate ?: new Date().clearTime()

        payment.confirmedDate = confirmedDate
        payment.clientPaymentDate = confirmedDate
        payment.creditDate = Payment.calculateEstimatedCreditDate(payment.providerId, installmentNumber, payment.confirmedDate)
        payment.estimatedCreditDate = payment.creditDate
        payment.creditCardTid = transactionVo.transactionIdentifier
		payment.creditCardUsn = transactionVo.uniqueSequentialNumber
		payment.status = PaymentStatus.CONFIRMED
		payment.billingType = BillingType.MUNDIPAGG_CIELO
		payment.deleted = false

        PaymentFeeVO paymentFeeVO = PaymentFeeVO.buildForCreditCard(payment, transactionVo.brand)
        payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)

		payment.save(failOnError: true)

        paymentCustodyService.saveIfNecessary(payment)

        creditCardTransactionInfoService.save(payment, installmentNumber, transactionVo)

		if (!payment.installment) receivableAnticipationSchedulingService.handlePaymentConfirmation(payment)

        if (!AsaasEnvironment.isSandbox()) asyncActionService.save(AsyncActionType.SAVE_RECEIVABLE_UNIT_ITEM, [paymentId: payment.id])

        paymentConfirmService.processPaymentDunningIfExists(payment)

		Invoice scheduledInvoice = payment.getScheduledInvoiceForPaymentConfirmation()
		if (scheduledInvoice) invoiceService.setAsPending(scheduledInvoice)
		if (previousPaymentStatus.isOverdue()) {
            invoiceService.applyInterestPlusFineValueIfNecessary(payment.getInvoice())
            receivableAnticipationValidationService.onPaymentRestore(payment)
        }

        if (doublePromotionService.hasValidDoublePromotionActivationV2(payment.provider)) {
            doublePromotionService.saveFreePixOrBankSlipPaymentFromDoublePromotionV2(payment.provider.id)
        }

        paymentSplitService.updateToAwaitingCreditIfExists(payment)
	}

    public Payment setCreditCardCaptureAsAwaitingRiskAnalysis(CapturedCreditCardTransactionVo transactionVo, BillingInfo billingInfo) {
        Payment payment = transactionVo.payment

        updateBillingInfo(payment, billingInfo)

		if (payment.installment) {
			setInstallmentAsAwaitingRiskAnalysis(payment.installment, payment, transactionVo)
		} else {
			setPaymentAsAwaitingRiskAnalysis(payment)
		}

        creditCardTransactionAnalysisService.save(payment, transactionVo)

        return payment
    }

    private void setInstallmentAsAwaitingRiskAnalysis(Installment installment, Payment payment, CapturedCreditCardTransactionVo transactionVo) {
		setInstallmentPaymentDate(installment)

		for (Payment remainingPayment in payment.installment.getRemainingPayments()) {
			setPaymentAsAwaitingRiskAnalysis(remainingPayment)
            PaymentFeeVO paymentFeeVO = PaymentFeeVO.buildForCreditCard(remainingPayment, transactionVo.brand)
            remainingPayment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
            remainingPayment.save(failOnError: true)
		}
	}

    private Installment setInstallmentPaymentDate(Installment installment) {
        installment.paymentDate = new Date().clearTime()
		installment.billingType = BillingType.MUNDIPAGG_CIELO
        return installment
    }

    private void setPaymentAsAwaitingRiskAnalysis(Payment payment) {
		payment.deleted = false
        payment.status = PaymentStatus.AWAITING_RISK_ANALYSIS
		payment.save(failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
    }

	public Payment executePaymentCredit(Payment payment) {
        if (payment.isReceived()) return payment

		if (!payment.isConfirmed() && !payment.status.isRefundInProgress()) throw new RuntimeException("ConfirmCreditCardReceipt: A cobrança [${payment.getInvoiceNumber()}] não pode ser recebida pois não está confirmada ou em processo de estorno.")

        if (payment.status.isRefundInProgress()) payment.hasRefundInProgress = true

        CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([paymentId: payment.id]).get()
        if (creditCardTransactionInfo) {
            creditCardTransactionInfo.credited = true
            creditCardTransactionInfo.save(failOnError: true)
		}

		payment.paymentDate = new Date().clearTime()
		if (!payment.creditDate) payment.creditDate = new Date().clearTime()

        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_RECEIVED)

        payment.status = PaymentStatus.RECEIVED

        if (promotionalCodeService.customerHasValidFeeDiscount(payment.provider)) {
            promotionalCodeService.consumeFeeDiscountPromotionalCodeAndSetNetValue(payment)
        } else if (promotionalCodeService.hasValidFreePaymentPromotionalCode(payment.provider)) {
            promotionalCodeService.consumeFreePaymentPromotionalCode(payment)
        }

		payment.save(failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment.installment ?: payment)

        receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(payment.provider, false)

		receivableUnitItemService.setAsAwaitingSettlement(payment)

		financialTransactionService.savePaymentReceived(payment, null)

		paymentSplitService.executeSplit(payment)
        paymentRefundService.executeAllPendingPaymentRefundIfNecessary(payment)

        receivableAnticipationService.debit(payment, false)

        notificationRequestService.cancelPaymentUnsentNotifications(payment)

        if (payment.installment) installmentService.notifyCustomerAboutInstallmentEnding(payment.installment)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        receivableHubPaymentOutboxService.savePaymentReceived(payment)

        chargedFeeService.savePaymentSmsNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentMessagingNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentOriginChannelFeeIfNecessary(payment)

		customerStageService.processCashInReceived(payment.provider)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RECEIVED)

        if (payment.hasRefundInProgress) {
            Boolean refundOnAcquirer = true
            if (creditCardTransactionInfo?.chargeback) {
                refundOnAcquirer = false
                AsaasLogger.warn("PaymentCreditCardService.executePaymentCredit >> Estorno de cobrança sem envio para adquirente [paymentId: ${payment.id}].")
            }

            if (payment.installment) {
                installmentService.refundCreditCard(payment.installment, refundOnAcquirer, [:])
            } else {
                paymentRefundService.refund(payment, [refundOnAcquirer: refundOnAcquirer])
            }
        }

        paymentCustodyService.processIfNecessary(payment)
        pixPaymentInfoService.purgeIfNecessary(payment)

		return payment
	}

	private void trackSegmentioEvent(Payment payment, String event) {
		try {
			Map dataMap = [providerEmail: payment.provider.email, paymentId: payment.id, customerEmail: payment.customerAccount.email, revenue: payment.getAsaasValue(), billingType: payment.billingType.toString(), dueDate: payment.dueDate, url:grailsLinkGenerator.link(controller: 'payment', action: 'show', id: payment.id, absolute: true)]
			asaasSegmentioService.track(payment.provider.id, event, dataMap)
		} catch (Exception exception) {
            AsaasLogger.error("PaymentCreditCardService.trackSegmentioEvent >> Erro ao rastrear evento Segmentio. [paymentId: ${payment?.id}]", exception)
		}
	}
}
