package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFirstConfirmedPayment
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentFineConfig
import com.asaas.domain.payment.PaymentInterestConfig
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.plan.Plan
import com.asaas.exception.BusinessException
import com.asaas.exception.PaymentAlreadyConfirmedException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.payment.PaymentFeeVO
import com.asaas.payment.PaymentStatus
import com.asaas.payment.PaymentUtils
import com.asaas.payment.validator.PaymentValidator
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.split.PaymentSplitCancellationReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class PaymentConfirmService {

    def asaasErpAccountingStatementService
	def asaasSegmentioService
    def boletoBatchFileItemService
    def chargedFeeService
    def creditBureauDunningService
    def customerEventService
	def customerInvoiceService
	def customerRegisterStatusService
	def debtRecoveryAssistanceDunningService
    def debtRecoveryNegotiationPaymentService
    def facebookEventService
	def financialTransactionService
	def freePaymentConfigService
    def installmentService
	def interestConfigService
	def invoiceService
    def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
	def notificationRequestService
    def paymentAfterConfirmEventService
    def paymentAfterCreditEventService
    def paymentAnticipableInfoService
    def paymentCustodyService
    def paymentDunningService
    def paymentFeeService
    def customerFirstConfirmedPaymentService
    def paymentFloatService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def paymentRefundService
    def paymentSplitService
    def paymentUpdateService
	def planPaymentService
	def promotionalCodeService
    def receivableAnticipationCancellationService
    def receivableAnticipationService
    def receivableAnticipationValidationService
    def referralService
    def tedTransactionService
	def transactionReceiptService

	public Payment confirmPayment(Payment payment, BigDecimal value, Date clientPaymentDate, BillingType billingType) {
        if (!payment.canConfirm()) throw new PaymentAlreadyConfirmedException("Pagamento ${payment.id} já foi confirmado.")

        PaymentStatus previousPaymentStatus = payment.status
        payment.onReceiving = true
        processReceivedValue(payment, value, clientPaymentDate)

        payment.clientPaymentDate = clientPaymentDate
        payment.confirmedDate = new Date().clearTime()
        payment.paymentDate = new Date().clearTime()
        payment.deleted = false
        payment.billingType = billingType
        payment.status = PaymentStatus.CONFIRMED

        Boolean hasFloat = paymentFloatService.applyPaymentFloatOnPaymentConfirmation(payment)

        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_CONFIRMED)

        if (receivableAnticipationService.paymentFeeChargedByAnticipationWhenPaymentReceiving(payment)) {
            payment.netValue = payment.value
        } else {
            payment = applyNetValue(payment)
        }

        if (payment.postalServiceStatus in PostalServiceStatus.notSentYet()) payment.postalServiceStatus = null

        AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
            payment.save(flush: true, failOnError: true)
        })

        paymentCustodyService.saveIfNecessary(payment)

        notificationRequestService.cancelPaymentUnsentNotifications(payment)

        Boolean shouldSendCustomerPaymentReceivedNotification = true
        if (payment.billingType.isTransfer()) shouldSendCustomerPaymentReceivedNotification = false
        if (payment.billingType.isPix()) shouldSendCustomerPaymentReceivedNotification = (PixTransaction.credit([column: "originType", payment: payment]).get()?.isDynamicQrCode())
        if (shouldSendCustomerPaymentReceivedNotification) notificationRequestService.save(payment.customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, payment, NotificationPriority.LOW)

        if (!hasFloat) {
            executePaymentCredit(payment)
        } else if (previousPaymentStatus.isOverdue()) {
            receivableAnticipationValidationService.onPaymentRestore(payment)
        } else {
            receivableAnticipationValidationService.onPaymentChange(payment)
        }

        transactionReceiptService.savePaymentReceived(payment)
        updateInstallmentBillingTypeIfNecessary(payment)
        paymentAfterConfirmEventService.save(payment, previousPaymentStatus)

        return payment
    }

    public void processPaymentDunningIfExists(Payment payment) {
        PaymentDunning paymentDunning = PaymentDunning.query([payment: payment]).get()
        if (!paymentDunning) return

        if (paymentDunning.type.isDebtRecoveryAssistance()) {
            paymentDunningService.delete(paymentDunning.payment)
        } else {
            creditBureauDunningService.onPaymentConfirm(paymentDunning)
        }
    }

    public Payment applyNetValue(Payment payment) {
        BigDecimal netValue

        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(payment)

        if (payment.billingType.isPix()) {
            Boolean shouldRecalculateNetValue = (PixTransaction.credit([column: "originType", payment: payment]).get()?.isDynamicQrCode())
            if (shouldRecalculateNetValue) {
                netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
            } else {
                netValue = payment.netValue
            }
        } else {
            netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        }

        if (netValue < 0) netValue = 0

        if (freePaymentConfigService.customerHasFreePaymentToConsume(payment.provider)) {
            consumeFreePaymentAndSetNetValue(payment, netValue)
        } else if (promotionalCodeService.hasValidFreePaymentPromotionalCode(payment.provider)) {
            consumeFreePaymentPromotionalCodeAndSetNetValue(payment, netValue)
        } else if (Plan.customerHasValidPlanPayment(payment.provider)) {
            consumePlanPaymentAndSetNetValue(payment, netValue)
        } else {
            payment.netValue = netValue
        }

        promotionalCodeService.consumeFeeDiscountPromotionalCodeAndSetNetValue(payment)

        return payment
    }

    public void executePaymentCredit(Payment payment) {
        if (!payment.status.isConfirmed()) {
            throw new RuntimeException("Não é possivel executar o crédito da cobrança [${payment.id}]: não está confirmada!")
        }

        if (![BillingType.BOLETO, BillingType.TRANSFER, BillingType.DEPOSIT, BillingType.PIX].contains(payment.billingType)) {
            throw new RuntimeException("Não é possivel executar o crédito da cobrança [${payment.id}]: billingType inválido!")
        }

        payment.onReceiving = true
        payment.status = PaymentStatus.RECEIVED
        payment.creditDate = new Date().clearTime()

        AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
            payment.save(flush: true, failOnError: true)
        })

        receivableAnticipationValidationService.onPaymentChange(payment)
        financialTransactionService.savePaymentReceived(payment, null)

        paymentSplitService.executeSplit(payment)
        paymentRefundService.executeAllPendingPaymentRefundIfNecessary(payment)

        chargedFeeService.savePaymentSmsNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentMessagingNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentOriginChannelFeeIfNecessary(payment)

        receivableAnticipationService.debit(payment, false)
        debtRecoveryNegotiationPaymentService.payDebtIfNecessary(payment)
        paymentCustodyService.processIfNecessary(payment)

        paymentAfterCreditEventService.save(payment)

        if (payment.billingType.isTransfer()) tedTransactionService.setTransactionAsDoneIfNecessary(payment)

        processPaymentDunningIfExists(payment)
	}

    public Payment confirmReceivedInCash(Customer customer, Long paymentId, Map params) {
        Payment payment = Payment.find(paymentId, customer.id)
        PaymentStatus previousPaymentStatus = payment.status
        Boolean shouldChargeDunningFee = payment.getDunning()?.shouldChargeReceivedInCashFee()

		validateConfirmReceivedInCash(payment, params)
		if (payment.hasErrors()) return payment
		if (!payment.canBeReceivedInCash()) throw new BusinessException(payment.businessErrorMessage)

		Date paymentDate = params.paymentDate instanceof Date ? params.paymentDate : CustomDateUtils.fromString(params.paymentDate, 'dd/MM/yy')

		if (payment.postalServiceStatus && payment.postalServiceStatus in PostalServiceStatus.notSentYet()) payment.postalServiceStatus = null

        processPaymentDunningReceivedInCashIfExists(payment)

        if (!shouldChargeDunningFee) {
            if (payment.value != Utils.toBigDecimal(params.value)) payment.originalValue = payment.value
            payment.value = Utils.toBigDecimal(params.value)
            payment.netValue = payment.value
        }
		payment.status = PaymentStatus.RECEIVED_IN_CASH
		payment.clientPaymentDate = paymentDate
		payment.paymentDate = paymentDate
		payment.onReceiving = true

        payment.avoidExplicitSave = true
        receivableAnticipationValidationService.onPaymentChange(payment)
        boletoBatchFileItemService.deleteRegistrationIfRegistered(payment)
        payment.avoidExplicitSave = false

        payment.save(flush: true, failOnError: true)

		notificationRequestService.cancelPaymentUnsentNotifications(payment)

		if (params.sendNotification) notificationRequestService.save(payment.customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, payment, NotificationPriority.HIGH)

        if (payment.installment) installmentService.notifyCustomerAboutInstallmentEnding(payment.installment)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment, null, true)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentReceivedInCash(payment, Utils.toBoolean(params.sendNotification))

		trackSegmentioEvent(payment, "Service :: Payment :: Pagamento recebido em dinheiro", null)

		Invoice scheduledInvoice = payment.getScheduledInvoiceForPaymentConfirmation()
		if (scheduledInvoice) {
			if (params.cancelScheduledInvoice) {
				customerInvoiceService.cancelInvoice(payment)
			} else {
				invoiceService.setAsPending(scheduledInvoice)
			}
		}

		if (previousPaymentStatus == PaymentStatus.OVERDUE && !params.cancelScheduledInvoice) invoiceService.applyInterestPlusFineValueIfNecessary(payment.getInvoice())

        chargedFeeService.savePaymentSmsNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentMessagingNotificationFeeIfNecessary(payment)
        chargedFeeService.savePaymentOriginChannelFeeIfNecessary(payment)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RECEIVED)
        paymentSplitService.cancelPending(payment, PaymentSplitCancellationReason.PAYMENT_RECEIVED_IN_CASH)

		return payment
	}

    public void executeFirstConfirmedPaymentActionsIfNecessary(Payment payment, Map additionalParameters) {
        customerEventService.saveFirstPaymentReceivedOnMethodIfPossible(payment.provider, BillingType.convert(payment.billingType))

        if (CustomerFirstConfirmedPayment.existsForCustomer(payment.provider.id)) return

        customerFirstConfirmedPaymentService.save(payment)

        trackSegmentioEvent(payment, "Service :: Payment :: Primeiro Pagamento Confirmado", additionalParameters)
        mobilePushNotificationService.notifyFirstPaymentReceived(payment)
        customerRegisterStatusService.notifyIncompleteInfoForCheckoutIfNecessary(payment.provider)
        facebookEventService.saveFirstPaymentReceivedEvent(payment.provider.id)
    }

    public void executeConfirmedPaymentsCredit() {
        List<Long> paymentIdList = Payment.automaticCreditableAfterFloat([column: "id", creditDate: new Date().clearTime(), "paymentSettlementSchedule[notExists]": true]).list()
        executeCreditForPaymentList(paymentIdList)
    }

    public void trackSegmentioEvent(Payment payment, String event, Map additionalParameters) {
        try {
            Map dataMap = [providerEmail: payment.provider.email, paymentId: payment.id, customerEmail: payment.customerAccount.email, revenue: payment.getAsaasValue(), billingType: payment.billingType.toString(), chargeType: getChargeTypeString(payment), dueDate: payment.dueDate]
            if (additionalParameters) {
                dataMap.putAll(additionalParameters)
            }

            asaasSegmentioService.track(payment.provider.id, event, dataMap)
        } catch (Exception e) {
            AsaasLogger.error("PaymentConfirmService.trackSegmentioEvent >> Erro ao enviar evento ${event} para o segmentio", e)
        }
    }

    private void executeCreditForPaymentList(List<Long> paymentIdList) {
        for (Long paymentId : paymentIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                executePaymentCredit(Payment.get(paymentId))
            }, [errorEmailSubject: "Erro ao creditar cobrança [${paymentId}] automaticamente"])
        }
    }

    private void validateConfirmReceivedInCash(Payment payment, Map params) {
        if (Utils.toBigDecimal(params.value) > payment.RECEIVED_IN_CASH_LIMIT_VALUE) {
            DomainUtils.addError(payment, "O valor de recebimento da cobrança não pode ser superior à ${FormUtils.formatCurrencyWithMonetarySymbol(payment.RECEIVED_IN_CASH_LIMIT_VALUE)}")
        }

        if (!params.paymentDate) {
            DomainUtils.addError(payment, "A data de pagamento da cobrança deve ser informada.")
        } else {
            Date todayMinusOneYear = CustomDateUtils.addMonths(new Date(), -12)
            Date paymentDate = params.paymentDate instanceof Date ? params.paymentDate : CustomDateUtils.fromString(params.paymentDate)
            if (paymentDate < todayMinusOneYear.clearTime()) DomainUtils.addError(payment, "A data de pagamento da cobrança não pode ser inferior à ${CustomDateUtils.fromDate(todayMinusOneYear)}")
            if (paymentDate < payment.dateCreated.clone().clearTime()) DomainUtils.addError(payment, "Escolha uma data posterior a data de criação da cobrança ${CustomDateUtils.fromDate(payment.dateCreated)} até a data atual.")
            if (paymentDate > new Date().clearTime()) DomainUtils.addError(payment, "A data selecionada ${CustomDateUtils.fromDate(paymentDate)} não pode ser posterior a data atual.")
        }

        if (!payment.getDunning()?.shouldChargeReceivedInCashFee() && (!Utils.toBigDecimal(params.value) || Utils.toBigDecimal(params.value) < Payment.MIN_VALUE_TO_RECEIVE_IN_CASH)) {
            DomainUtils.addError(payment, Utils.getMessageProperty("payment.minValue.receivedInCash.obs", [FormUtils.formatCurrencyWithMonetarySymbol(Payment.MIN_VALUE_TO_RECEIVE_IN_CASH)]))
        }
	}

    private String getChargeTypeString(Payment payment) {
        if (payment.getSubscription()) return ChargeType.RECURRENT.toString()
        if (payment.installment) return ChargeType.INSTALLMENT.toString()
        return ChargeType.DETACHED.toString()
    }

    private void processReceivedValue(Payment payment, BigDecimal receivedValue, Date clientPaymentDate) {
        if (payment.duplicatedPayment) return

        if (payment.value != receivedValue) {
            PaymentDiscountConfig paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: payment.id]).get()

            if (paymentDiscountConfig?.valid(clientPaymentDate) && payment.canApplyCurrentBankSlipDiscount()) {
                payment.discountValue = paymentDiscountConfig.calculateDiscountValue() ?: null
            }
        }

        payment.interestValue = (payment.value != receivedValue) ? getExpectedInterestValue(payment, clientPaymentDate) : null

        if (payment.value != receivedValue || payment.interestValue > 0 || payment.discountValue > 0) {
            payment.originalValue = payment.value
            payment.value = receivedValue
            payment.save(flush: true, failOnError: true)
        }
    }

    private BigDecimal getExpectedInterestValue(Payment payment, Date paymentDate) {
        if (!PaymentUtils.paymentDateHasBeenExceeded(payment) || !payment.canApplyCurrentBankSlipFineAndInterest()) return 0

        return interestConfigService.calculateFineAndInterestValue(payment, paymentDate)
    }

	private void updateInstallmentBillingTypeIfNecessary(Payment payment) {
		if (!payment.installment || payment.installment.billingType == payment.billingType) return

		Installment installment = payment.installment
		List<Payment> pendingOrOverduePaymentList = Payment.pendingOrOverdue([installmentId: installment.id]).list()

		BillingType newBillingType = payment.billingType
        if (PaymentUndefinedBillingTypeConfig.equivalentToBoleto(payment) || newBillingType.isPix()) newBillingType = BillingType.BOLETO

        for (Payment it : pendingOrOverduePaymentList) {
            processPendingPaymentBillingTypeUpdateIfNecessary(it, newBillingType)
        }

        if (installment.allPaymentsAreReceived() && installment.getNotDeletedPayments().findAll { it.billingType.isPix() }) {
            newBillingType = BillingType.PIX
        }

		installment.billingType = newBillingType
		installment.save(failOnError: true)
	}

    private void consumeFreePaymentAndSetNetValue(Payment payment, BigDecimal netValue) {
        payment.netValue = netValue
        freePaymentConfigService.consumeFreePaymentIfPossible(payment)
    }

	private void consumeFreePaymentPromotionalCodeAndSetNetValue(Payment payment, BigDecimal netValue) {
		payment.netValue = netValue
		promotionalCodeService.consumeFreePaymentPromotionalCode(payment)
	}

	private void consumePlanPaymentAndSetNetValue(Payment payment, BigDecimal netValue) {
		planPaymentService.consumePlanPayment(payment)
		payment.netValue = payment.value
	}

    private void processPaymentDunningReceivedInCashIfExists(Payment payment) {
        PaymentDunning paymentDunning = PaymentDunning.query([payment: payment]).get()
        if (!paymentDunning) return

        if (paymentDunning.type.isDebtRecoveryAssistance()) {
            debtRecoveryAssistanceDunningService.confirmReceivedInCash(paymentDunning)
        } else {
            creditBureauDunningService.confirmReceivedInCashIfNecessary(paymentDunning)
        }
    }

    private void processPendingPaymentBillingTypeUpdateIfNecessary(Payment payment, BillingType newBillingType) {
        if (payment.billingType == newBillingType) return

        List<Map> updatedFields = [[fieldName: "billingType", oldValue: payment.billingType, newValue: newBillingType]]

        payment.billingType = newBillingType

        ReceivableAnticipationNonAnticipableReasonVO reasonVO = receivableAnticipationValidationService.validatePaymentAnticipable(payment)
        if (reasonVO) {
            receivableAnticipationValidationService.setSchedulable(payment, reasonVO)
        } else {
            paymentAnticipableInfoService.sendToAnalysisQueue(payment.id)
        }

        payment.save(failOnError: true)

        paymentAnticipableInfoService.updateIfNecessary(payment)

        if (PaymentValidator.validateIfBankSlipCanBeRegistered(payment, payment.boletoBank ?: payment.provider.boletoBank)) {
            PaymentInterestConfig paymentInterestConfig = PaymentInterestConfig.query([paymentId: payment.id]).get()
            PaymentFineConfig paymentFineConfig = PaymentFineConfig.query([paymentId: payment.id]).get()
            PaymentDiscountConfig paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: payment.id]).get()
            paymentUpdateService.processBankSlipRegisterUpdate(payment, paymentInterestConfig, paymentFineConfig, paymentDiscountConfig, updatedFields)
        } else if (paymentUpdateService.billingTypeHasBeenUpdatedToBoletoOrUndefined(payment, updatedFields)) {
            paymentUpdateService.updateNossoNumero(payment)
        }
    }
}
