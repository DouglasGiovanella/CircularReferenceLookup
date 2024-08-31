package com.asaas.service.chargeback

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.chargeback.ChargebackReason
import com.asaas.chargeback.ChargebackScheduledSettlementStatus
import com.asaas.chargeback.ChargebackStatus
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargeback.ChargebackScheduledSettlement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.exception.receivableanticipation.CannotCancelAnticipationPartnerAcquisitionException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.paymentcustody.PaymentCustodyFinishReason
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.springframework.dao.CannotAcquireLockException

@Transactional
class ChargebackService {

    def asyncActionService
    def asaasCardRechargeService
    def asaasMoneyTransactionChargebackService
    def billService
    def chargebackScheduledSettlementService
    def creditTransferRequestService
    def customerAlertNotificationService
    def customerInteractionService
    def customerMessageService
    def financialTransactionService
    def grailsApplication
    def internalLoanService
    def installmentService
    def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentCreditCardService
    def paymentCustodyService
    def paymentRefundService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
    def receivableAnticipationLimitRecalculationService
    def receivableAnticipationValidationService
    def receivableHubPaymentOutboxService
    def asaasErpAccountingStatementService
    def riskAnalysisChargebackService

    public Chargeback save(Long paymentId, Long installmentId, ChargebackReason reason, Map params) {
        if (!Chargeback.isChargebackable(paymentId, installmentId)) {
            throw new BusinessException("Não é possível iniciar o processo de chargeback " + (paymentId ? "da cobrança [${paymentId}]" : "do parcelamento [${installmentId}]"))
        }

        Chargeback validatedChargeback = validateSave(reason, params)
        if (validatedChargeback.hasErrors()) return validatedChargeback

        Chargeback chargeback = new Chargeback()
        chargeback.reason = reason
        chargeback.status = ChargebackStatus.REQUESTED
        chargeback.originDisputeDate = CustomDateUtils.toDate(params.originDisputeDate)
        chargeback.daysToSendDisputeDocuments = Chargeback.DAYS_TO_SEND_DISPUTE_DOCUMENTS
        chargeback.publicId = UUID.randomUUID()

        if (installmentId) {
            Installment installment = Installment.get(installmentId)
            chargeback.installment = installment
            chargeback.customer = installment.getProvider()

            Payment payment = installment.getFirstConfirmedOrReceivedPayment()
            if (payment) {
                chargeback.transactionIdentifier = payment.creditCardTid
                chargeback.originTransactionDate = payment.confirmedDate
            }
        } else {
            Payment payment = Payment.get(paymentId)
            chargeback.payment = payment
            chargeback.customer = payment.provider
            chargeback.transactionIdentifier = payment.creditCardTid
            chargeback.originTransactionDate = payment.confirmedDate
        }

        chargeback.creditCardBrand = chargeback.getPaymentList().first().billingInfo.creditCardInfo.brand
        chargeback.value = chargeback.calculateTotalPaymentValue()
        chargeback.save(failOnError: false)

        if (chargeback.hasErrors()) return chargeback

        for (Payment payment : chargeback.getPaymentList()) {
            updatePaymentToChargebackRequested(chargeback, payment)
        }

        receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(chargeback.customer, false)

        String customerInteractionDescription = chargeback.payment ? "Recebido chargeback da cobrança nr. ${chargeback.payment.getInvoiceNumber()}." : "Recebido chargeback do parcelamento nr. ${chargeback.installment.getInvoiceNumber()}."
        customerInteractionDescription += " Valor: ${FormUtils.formatCurrencyWithoutMonetarySymbol(chargeback.value)}."

        if (chargeback.reason) customerInteractionDescription += " Motivo: ${Utils.getEnumLabel(reason)}."

        Date lastDateToSendDisputeDocuments = chargeback.getLastDateToSendDisputeDocuments(true)

        customerInteractionDescription += " Data limite para envio dos documentos para abertura de contestação ${CustomDateUtils.formatDate(lastDateToSendDisputeDocuments)}."

        customerInteractionService.save(chargeback.customer, "${customerInteractionDescription}")

        Map cancelledCheckouts = cancelCheckoutsToDebitChargeback(chargeback)

        asaasMoneyTransactionChargebackService.saveIfNecessary(chargeback)

        customerMessageService.sendChargebackRequested(chargeback, cancelledCheckouts)
        customerAlertNotificationService.notifyChargebackRequested(chargeback)
        mobilePushNotificationService.notifyChargebackRequested(chargeback)
        riskAnalysisChargebackService.saveAsyncActionIfNecessary(chargeback.customer, chargeback.id)

        return chargeback
    }

    public void confirmChargebacksWithExpiredDispute() {
        List<Long> chargebackIdList = Chargeback.query(["column": "id", "status": ChargebackStatus.REQUESTED]).list()

        for (Long chargebackId in chargebackIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                Chargeback chargeback = Chargeback.get(chargebackId)

                Boolean isChargebackExpiredDispute = CustomDateUtils.setTimeToEndOfDay(chargeback.getLastDateToSendDisputeDocuments(false)) < new Date()
                if (isChargebackExpiredDispute) confirm(chargebackId)
            }, [logErrorMessage: "ChargebackService.confirmAllExpiredDisputes >> Erro ao confirmar automaticamente o chargeback expirado ID ${chargebackId}"])
        }
    }

    public Chargeback confirm(Long id) {
        Chargeback chargeback = Chargeback.get(id)

        if (ChargebackScheduledSettlement.existsPendingSettlement(id, [:]).get().asBoolean()) {
            DomainUtils.addError(chargeback, "Chargeback não pode ser confirmado pois existe liquidação agendada para a cobrança")
            return chargeback
        }

        if (!chargeback.canBeConfirmed()) throw new Exception("O chargeback não pode ser confirmado")

        chargeback.finishDate = new Date().clearTime()
        chargeback.status = ChargebackStatus.DONE
        chargeback.save(failOnError: true)

        for (Payment payment : chargeback.getPaymentList()) {
            reverseChargebackFinancialTransactionIfNecessary(chargeback, payment)

            payment.hasRefundInProgress = true
            payment.status = payment.paymentDate ? PaymentStatus.RECEIVED : PaymentStatus.CONFIRMED
            payment.save(failOnError: true)
        }

        if (chargeback.payment) {
            paymentRefundService.refund(chargeback.payment, [refundOnAcquirer: false])
        } else {
            installmentService.refundCreditCard(chargeback.installment, false, [:])
        }

        receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(chargeback.customer, false)

        customerMessageService.sendChargebackDone(chargeback)
        customerAlertNotificationService.notifyChargebackConfirmed(chargeback)

        return chargeback
    }

    public void reverse(Long id) {
        Chargeback chargeback = Chargeback.get(id)

        if (!chargeback.isInDispute()) throw new BusinessException("O chargeback não esta em contestação")

        if (ChargebackScheduledSettlement.existsPendingSettlement(id, [:]).get().asBoolean()) throw new BusinessException("Chargeback não pode ser revertido pois existe liquidação agendada para a cobrança")

        chargeback.status = ChargebackStatus.REVERSED
        chargeback.save(failOnError: true)

        for (Payment payment : chargeback.getPaymentList()) {
            if (payment.paymentDate) {
                payment.status = PaymentStatus.AWAITING_CHARGEBACK_REVERSAL
                paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_AWAITING_CHARGEBACK_REVERSAL)
            } else {
                reverseChargebackFinancialTransactionIfNecessary(chargeback, payment)

                payment.status = PaymentStatus.CONFIRMED

                final Integer toleranceDaysToScheduleSettlement = 2
                Date chargebackSettleToleranceCreditDate = CustomDateUtils.addBusinessDays(new Date().clearTime(), toleranceDaysToScheduleSettlement)
                if (payment.creditDate < chargebackSettleToleranceCreditDate) {
                    payment.creditDate = chargebackSettleToleranceCreditDate
                }

                asyncActionService.save(AsyncActionType.RECEIVABLE_UNIT_ITEM_CHARGEBACK_EVENT, [paymentId: payment.id, chargebackStatus: ChargebackStatus.REVERSED.toString()])
                paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CONFIRMED)
                receivableHubPaymentOutboxService.savePaymentChargebackReversed(payment)
            }

            payment.save(failOnError: true)

            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        receivableAnticipationValidationService.onPaymentChange(chargeback.installment ?: chargeback.payment)

        asaasMoneyTransactionChargebackService.reverseIfNecessary(chargeback)

        receivableAnticipationLimitRecalculationService.addCustomerToRecalculateLimitIfNecessary(chargeback.customer, false)
    }

    public void creditReversal(Long id) {
        Chargeback chargeback = Chargeback.get(id)

        List<Payment> chargebackReversalList = chargeback.getPaymentList().findAll { it.status == PaymentStatus.AWAITING_CHARGEBACK_REVERSAL }

        for (Payment payment : chargebackReversalList) {
            creditReversalForPayment(chargeback, payment)
        }

        asaasMoneyTransactionChargebackService.reverseIfNecessary(chargeback)
    }

    public void creditReversal(Long id, Long paymentId) {
        Chargeback chargeback = Chargeback.get(id)

        Payment payment = chargeback.getPaymentList().find { it.id == paymentId }

        if (payment.status != PaymentStatus.AWAITING_CHARGEBACK_REVERSAL) return

        creditReversalForPayment(chargeback, payment)

        asaasMoneyTransactionChargebackService.reverseIfNecessary(chargeback)
    }

    public Chargeback updateToInDispute(Long id) {
        Chargeback chargeback = Chargeback.get(id)

        if (!chargeback.canBeConfirmed()) throw new BusinessException("Não pode ser feita a contestação do chargeback")

        if (ChargebackScheduledSettlement.existsPendingSettlement(id, [:]).get().asBoolean()) throw new BusinessException("Chargeback não pode ser atualizado para em disputa pois existe liquidação agendada para a cobrança")

        chargeback.documentsSentDate = new Date()
        chargeback.status = ChargebackStatus.IN_DISPUTE
        chargeback.save(failOnError: true)

        for (Payment payment : chargeback.getPaymentList()) {
            payment.status = PaymentStatus.CHARGEBACK_DISPUTE
            payment.save(failOnError: true)

            receivableAnticipationValidationService.onPaymentChange(payment)

            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CHARGEBACK_DISPUTE)

            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        customerAlertNotificationService.notifyChargebackContested(chargeback)

        return chargeback
    }

    public Chargeback updateToDisputeLost(Long id) {
        Chargeback chargeback = Chargeback.get(id)

        if (!chargeback.isInDispute()) throw new Exception("O chargeback não esta em contestação")

        chargeback.status = ChargebackStatus.DISPUTE_LOST
        chargeback.save(failOnError: true)

        for (Payment payment : chargeback.getPaymentList()) {
            payment.status = PaymentStatus.CHARGEBACK_REQUESTED
            payment.save(failOnError: true)

            receivableAnticipationValidationService.onPaymentChange(payment)

            paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CHARGEBACK_REQUESTED)

            asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        return chargeback
    }

    public Boolean processPaymentWithChargebackRequestedCredit(Date creditDate) {
        final Integer numberOfOperations = 200

        Boolean hasConfirmedPaymentsToSettle = Payment.confirmedPaymentToSettle(BillingType.MUNDIPAGG_CIELO, creditDate, [exists: true]).get().asBoolean()
        if (hasConfirmedPaymentsToSettle) return true

        List<Long> chargebackScheduledSettlementIdList = ChargebackScheduledSettlement.query([column: "id", "creditDate": creditDate, "status": ChargebackScheduledSettlementStatus.PENDING]).list(max: numberOfOperations)
        if (!chargebackScheduledSettlementIdList) return false

        Utils.forEachWithFlushSession(chargebackScheduledSettlementIdList, 50, { Long chargebackScheduledSettlementId ->
            Utils.withNewTransactionAndRollbackOnError({
                ChargebackScheduledSettlement chargebackScheduledSettlement = ChargebackScheduledSettlement.get(chargebackScheduledSettlementId)

                Payment payment = chargebackScheduledSettlement.payment

                if (!payment.isChargebackRequested()) {
                    throw new RuntimeException("Cobrança não está em processo de chargeback. [paymentId: ${payment.id}]")
                }

                if (payment.isReceived()) {
                    throw new RuntimeException("Cobrança já foi recebida. [paymentId: ${payment.id}]")
                }

                if (payment.creditDate != creditDate) {
                    throw new RuntimeException("A data de crédito foi alterada. [paymentId: ${payment.id}, creditDate: ${payment.creditDate}]")
                }

                if (!payment.billingType.isCreditCard()) {
                    throw new RuntimeException("O tipo da cobrança foi alterado. [paymentId: ${payment.id}, billingType: ${payment.billingType}]")
                }
                payment.status = PaymentStatus.CONFIRMED
                paymentCreditCardService.executePaymentCredit(payment)
                updatePaymentToChargebackRequested(chargebackScheduledSettlement.chargeback, payment)
                chargebackScheduledSettlementService.setAsDone(chargebackScheduledSettlement)
            }, [onError: { Exception exception ->
                if (exception instanceof CannotCancelAnticipationPartnerAcquisitionException || exception instanceof CannotAcquireLockException) {
                    AsaasLogger.warn("ChargebackService.processPaymentWithChargebackRequestedCredit >> Não foi possível executar crédito da cobrança devido a bloqueio do registro [chargebackScheduledSettlementId: ${chargebackScheduledSettlementId}].")
                    return true
                }

                AsaasLogger.error("ChargebackService.processPaymentWithChargebackRequestedCredit >> Erro ao processar liquidação de cartão de crédito para a cobrança. [chargebackScheduledSettlementId: ${chargebackScheduledSettlementId}].", exception)
            }])
        })

        return true
    }

    private Chargeback validateSave(ChargebackReason chargebackReason, Map params) {
        Chargeback chargeback = new Chargeback()

        if (!chargebackReason) {
            DomainUtils.addError(chargeback, "O motivo deve ser informado")
        }

        if (!params.originDisputeDate) {
            DomainUtils.addError(chargeback, "A data do início da disputa deve ser informada")
        }

        return chargeback
    }

    private Map cancelCheckoutsToDebitChargeback(Chargeback chargeback) {
        if (FinancialTransaction.getCustomerBalance(chargeback.customer) >= 0) return [:]

        Map cancelledCheckouts = [:]

        cancelledCheckouts.transfers = creditTransferRequestService.cancelWhileBalanceIsNegative(chargeback.customer).collect { it.transfer }
        cancelledCheckouts.recharges = asaasCardRechargeService.cancelWhileBalanceIsNegative(chargeback.customer)
        cancelledCheckouts.bills = billService.cancelWhileBalanceIsNegative(chargeback.customer)

        return cancelledCheckouts
    }

    private Payment updatePaymentToChargebackRequested(Chargeback chargeback, Payment payment) {
        receivableAnticipationCancellationService.cancelPending(payment, ReceivableAnticipationCancelReason.PAYMENT_CHARGEBACK_REQUESTED)

        if (keepChargebackBalanceBlockPending(payment)) {
            chargebackScheduledSettlementService.save(chargeback.id, payment)
        } else if (payment.isReceived() || payment.hasCreditedAnticipation()) {
            paymentCustodyService.finishIfNecessary(payment, PaymentCustodyFinishReason.CHARGEBACK)

            FinancialTransaction financialTransaction = financialTransactionService.saveChargeback(chargeback, payment)
            internalLoanService.saveIfNecessary(financialTransaction)
            chargeback.isBalanceBlocked = true
            chargeback.save(failOnError: true)
        }

        payment.status = PaymentStatus.CHARGEBACK_REQUESTED
        payment.save(failOnError: true)

        asyncActionService.save(AsyncActionType.RECEIVABLE_UNIT_ITEM_CHARGEBACK_EVENT, [paymentId: payment.id, chargebackId: chargeback.id, chargebackStatus: ChargebackStatus.REQUESTED.toString()])

        receivableAnticipationValidationService.onPaymentChange(payment)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CHARGEBACK_REQUESTED)
        receivableHubPaymentOutboxService.savePaymentChargebackRequested(payment)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)

        return payment
    }

    private void reverseChargebackFinancialTransactionIfNecessary(Chargeback chargeback, Payment payment) {
        if (!chargeback.isBalanceBlocked) return

        FinancialTransaction transaction = financialTransactionService.reverseChargebackIfNecessary(chargeback, payment)
        if (!transaction) return

        internalLoanService.cancelPendingLoan(transaction.reversedTransaction)
    }

    private void creditReversalForPayment(Chargeback chargeback, Payment payment) {
        reverseChargebackFinancialTransactionIfNecessary(chargeback, payment)

        if (!payment.paymentDate) {
            payment.status = PaymentStatus.CONFIRMED

            if (new Date().clearTime() >= payment.creditDate) {
                paymentCreditCardService.executePaymentCredit(payment)
            }
        } else {
            payment.status = PaymentStatus.RECEIVED
        }

        payment.save(failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment)

        PushNotificationRequestEvent event = payment.status == PaymentStatus.RECEIVED ? PushNotificationRequestEvent.PAYMENT_RECEIVED : PushNotificationRequestEvent.PAYMENT_CONFIRMED
        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, event)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
    }

    private Boolean keepChargebackBalanceBlockPending(Payment payment) {
        if (payment.isReceived()) return false
        if (payment.hasCreditedAnticipation()) return false
        if (!payment.isConfirmed()) return false

        Date creditDate = payment.creditDate.clone().clearTime()
        if (CustomDateUtils.isSameDayOfYear(new Date(), creditDate)) return true

        Date settlementScheduleLimitDate = CustomDateUtils.subtractMinutesAndSetForLastBusinessDay(creditDate, grailsApplication.config.payment.settlement.minutesToScheduleBeforeSettlement)
        if (new Date() > settlementScheduleLimitDate) return true

        return false
    }
}
