package com.asaas.service.creditcard

import com.asaas.creditcard.CapturedCreditCardTransactionVo
import com.asaas.creditcard.CreditCardTransactionAnalysisStatus
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentGatewayInfo
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationReceiver
import com.asaas.payment.PaymentStatus
import com.asaas.payment.PaymentUtils
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CreditCardTransactionAnalysisService {

    def asaasMoneyPaymentCompromisedBalanceService
    def asaasMoneyTransactionInfoService
    def creditCardAcquirerFeeService
    def creditCardService
    def paymentCreditCardService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationRequestService
    def treasureDataService
    def receivableAnticipationValidationService

	public CreditCardTransactionAnalysis save(Payment payment, CapturedCreditCardTransactionVo transactionVo) {
        Date confirmedDate = transactionVo.confirmedDate ?: new Date().clearTime()

        CreditCardTransactionAnalysis transactionAnalysis = new CreditCardTransactionAnalysis()
        transactionAnalysis.transactionIdentifier = transactionVo.transactionIdentifier
        transactionAnalysis.uniqueSequentialNumber = transactionVo.uniqueSequentialNumber
        transactionAnalysis.acquirer = transactionVo.acquirer
        transactionAnalysis.gateway = transactionVo.gateway
        transactionAnalysis.customer = payment.provider
        transactionAnalysis.customerAccount = payment.customerAccount
        transactionAnalysis.confirmedDate = confirmedDate
        transactionAnalysis.mcc = transactionVo.mcc

        if (payment.installment) {
            transactionAnalysis.installment = payment.installment
        } else {
            transactionAnalysis.payment = payment
        }

        transactionAnalysis.save(failOnError: true)

        List<Payment> paymentList = transactionAnalysis.getPaymentList([status: PaymentStatus.AWAITING_RISK_ANALYSIS])
        savePaymentPushNotification(paymentList, PushNotificationRequestEvent.PAYMENT_AWAITING_RISK_ANALYSIS)

        notificationDispatcherPaymentNotificationOutboxService.saveCreditCardTransactionAwaitingAnalysis(payment)
        notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.PAYMENT_AWAITING_RISK_ANALYSIS, payment, NotificationPriority.HIGH, NotificationReceiver.PROVIDER)

        return transactionAnalysis
    }

    public CreditCardTransactionAnalysis approve(Long id) {
        CreditCardTransactionAnalysis transactionAnalysis = CreditCardTransactionAnalysis.get(id)

        BusinessValidation validatedBusiness = transactionAnalysis.canBeAnalyzed()
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new CreditCardTransactionAnalysis(), validatedBusiness.getFirstErrorMessage())

        List<Payment> paymentList = transactionAnalysis.getPaymentList([status: PaymentStatus.AWAITING_RISK_ANALYSIS])
        CapturedCreditCardTransactionVo transactionVo = parseCapturedCreditCardTransactionVo(transactionAnalysis, paymentList[0])

        for (Payment payment : paymentList) {
            payment.status = PaymentUtils.paymentDateHasBeenExceeded(payment) ? PaymentStatus.OVERDUE : PaymentStatus.PENDING
            payment.save(flush: true, failOnError: true)
        }

        savePaymentPushNotification(paymentList, PushNotificationRequestEvent.PAYMENT_APPROVED_BY_RISK_ANALYSIS)

        paymentCreditCardService.confirmCreditCardCapture(transactionVo, paymentList[0].billingInfo)

        transactionAnalysis.status = CreditCardTransactionAnalysisStatus.APPROVED
        transactionAnalysis.analyst = UserUtils.getCurrentUser()
        transactionAnalysis.analysisDate = new Date()
        transactionAnalysis.save(failOnError: true)

        asaasMoneyPaymentCompromisedBalanceService.refund(transactionAnalysis)

        treasureDataService.track(transactionAnalysis.customer, TreasureDataEventType.CREDIT_CARD_TRANSACTION_ANALYZED, [transactionAnalysisId: transactionAnalysis.id, status: CreditCardTransactionAnalysisStatus.APPROVED])

        return transactionAnalysis
    }

    private CapturedCreditCardTransactionVo parseCapturedCreditCardTransactionVo(CreditCardTransactionAnalysis transactionAnalysis, Payment payment) {
        CapturedCreditCardTransactionVo capturedCreditCardTransactionVo = new CapturedCreditCardTransactionVo()
		capturedCreditCardTransactionVo.payment = payment
		capturedCreditCardTransactionVo.transactionIdentifier = transactionAnalysis.transactionIdentifier
		capturedCreditCardTransactionVo.acquirer = transactionAnalysis.acquirer
		capturedCreditCardTransactionVo.gateway = transactionAnalysis.gateway
        capturedCreditCardTransactionVo.confirmedDate = transactionAnalysis.confirmedDate
        capturedCreditCardTransactionVo.mcc = transactionAnalysis.mcc

        if (payment.billingInfo) {
            Integer installmentCount = payment.installment ? payment.installment.listCreditCardPayments().findAll( { it.isAwaitingRiskAnalysis() } ).size() : 1
            capturedCreditCardTransactionVo.acquirerFee = creditCardAcquirerFeeService.findAcquirerFee(transactionAnalysis.acquirer,
                                                                                                       payment.billingInfo.creditCardInfo.brand,
                                                                                                       capturedCreditCardTransactionVo.mcc,
                                                                                                       installmentCount)
        }

		if (transactionAnalysis.uniqueSequentialNumber) capturedCreditCardTransactionVo.uniqueSequentialNumber = Long.valueOf(transactionAnalysis.uniqueSequentialNumber)

        return capturedCreditCardTransactionVo
    }

    public CreditCardTransactionAnalysis reprove(Long id) {
        CreditCardTransactionAnalysis transactionAnalysis = CreditCardTransactionAnalysis.get(id)

        BusinessValidation validatedBusiness = transactionAnalysis.canBeAnalyzed()
        if (!validatedBusiness.isValid()) return DomainUtils.addError(new CreditCardTransactionAnalysis(), validatedBusiness.getFirstErrorMessage())

        List<Payment> paymentList = transactionAnalysis.getPaymentList([status: PaymentStatus.AWAITING_RISK_ANALYSIS])

        Map creditCardAuthorizationInfoMap = CreditCardAuthorizationInfo.query([columnList: ["transactionReference", "amountInCents", "requestKey"], transactionIdentifier: transactionAnalysis.transactionIdentifier]).get()

        String refundReferenceCode = transactionAnalysis.gateway.isMundipagg() ? PaymentGatewayInfo.query(["column": "mundiPaggOrderKey", "paymentId": paymentList.first().id]).get() : creditCardAuthorizationInfoMap?.requestKey

        Map refundResponse = creditCardService.processRefund(
            paymentList.first().provider,
            paymentList.first().customerAccount,
            transactionAnalysis.gateway,
            transactionAnalysis.transactionIdentifier,
            creditCardAuthorizationInfoMap?.transactionReference,
            creditCardAuthorizationInfoMap?.amountInCents,
            refundReferenceCode
        )

        if (!refundResponse.success) throw new BusinessException("Não foi possível estornar a transação. Entre em contato com o time de engenharia.")

        for (Payment payment : paymentList) {
            payment.billingInfo = null
            payment.status = PaymentUtils.paymentDateHasBeenExceeded(payment) ? PaymentStatus.OVERDUE : PaymentStatus.PENDING
            payment.save(flush: true, failOnError: true)

            asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        if (paymentList[0].subscription) removeBillingInfoFromSubscription(paymentList[0].subscription)
        receivableAnticipationValidationService.onPaymentChange(transactionAnalysis.installment ?: transactionAnalysis.payment)
        notifyPaymentReprovedByRiskAnalysis(paymentList[0])
        notificationDispatcherPaymentNotificationOutboxService.saveCreditCardTransactionReprovedByAnalysis(paymentList[0])

        transactionAnalysis.status = CreditCardTransactionAnalysisStatus.DENIED
        transactionAnalysis.analyst = UserUtils.getCurrentUser()
        transactionAnalysis.analysisDate = new Date()
        transactionAnalysis.save(failOnError: true)

        asaasMoneyPaymentCompromisedBalanceService.refund(transactionAnalysis)

        savePaymentPushNotification(paymentList, PushNotificationRequestEvent.PAYMENT_REPROVED_BY_RISK_ANALYSIS)

        treasureDataService.track(transactionAnalysis.customer, TreasureDataEventType.CREDIT_CARD_TRANSACTION_ANALYZED, [transactionAnalysisId: transactionAnalysis.id, status: CreditCardTransactionAnalysisStatus.DENIED])

        return transactionAnalysis
    }

    public void refundPendingAnalysis() {
        List<Long> creditCardTransactionAnalysisIdList = CreditCardTransactionAnalysis.pending([column: "id"]).list()
        if (!creditCardTransactionAnalysisIdList) return

        for (Long analysisId in creditCardTransactionAnalysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CreditCardTransactionAnalysis transactionAnalysis = reprove(analysisId)
                if (transactionAnalysis.hasErrors()) AsaasLogger.error("refundPendingAnalysis >> Nao foi possivel reprovar a transacao [${analysisId}]")
            }, [logErrorMessage: "refundPendingAnalysis >> Erro ao estornar analise manual de cartao de credito [${analysisId}]"])
        }
    }

    public void notifyPaymentReprovedByRiskAnalysis(Payment payment) {
        if (!payment.provider.isAsaasMoneyProvider()) {
            notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.PAYMENT_REPROVED_BY_RISK_ANALYSIS, payment, NotificationPriority.HIGH, NotificationReceiver.CUSTOMER)
        }

        notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.PAYMENT_REPROVED_BY_RISK_ANALYSIS, payment, NotificationPriority.HIGH, NotificationReceiver.PROVIDER)
    }

    public void removeBillingInfoFromSubscription(Subscription subscription) {
        List<Payment> paymentListFromSubscription = subscription.getPendingPayments()

        for (Payment payment : paymentListFromSubscription) {
            payment.billingInfo = null
            payment.save(flush: true, failOnError: true)
        }

        subscription.billingInfo = null
        subscription.save(flush: true, failOnError: true)
    }

    private savePaymentPushNotification(List<Payment> paymentList, PushNotificationRequestEvent event) {
        for (Payment payment : paymentList) {
            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, event)
        }
    }
}
