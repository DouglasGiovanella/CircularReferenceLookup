package com.asaas.service.paymentdunning

import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItem
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.payment.PaymentDunningStatus
import com.asaas.payment.PaymentStatus
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CreditBureauDunningService {

    def bankSlipOnlineRegistrationService
    def boletoBatchFileItemService
    def chargedFeeService
    def creditBureauDunningBatchItemService
    def customerMessageService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentBankSlipInfoService
    def paymentDunningService
    def paymentDunningStatusHistoryService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationBlocklistService
    def asaasErpAccountingStatementService

    public PaymentDunning cancel(Long customerId, Long paymentDunningId, PaymentDunningCancellationReason cancellationReason) {
        PaymentDunning paymentDunning = PaymentDunning.find(paymentDunningId, Customer.get(customerId))
        return cancel(paymentDunning, cancellationReason, null)
    }

    public PaymentDunning cancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason) {
        return cancel(paymentDunning, cancellationReason, null)
    }

    public PaymentDunning cancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason, String description) {
        if (!paymentDunning.type.isCreditBureau()) throw new BusinessException("Não é possível cancelar essa negativação pois não é uma Negativação via Serasa.")
        if (!cancellationReason.isPaymentConfirmed() && paymentDunning.hasCreditBureauPartnerProcess() && paymentDunning.payment.isConfirmed()) throw new BusinessException("Não é possível cancelar essa negativação pois a cobrança está Confirmada.")
        if (paymentDunning.status.isCancelled()) return paymentDunning
        if (paymentDunning.status.isProcessingCancellation()) return paymentDunning

        BusinessValidation validatedBusiness = paymentDunning.canBeCancelled()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(paymentDunning, validatedBusiness.asaasErrors[0].getMessage())
            return paymentDunning
        }

        if (paymentDunning.status.isProcessed() || paymentDunning.status.isAwaitingPartnerApproval()) {
            paymentDunning.status = PaymentDunningStatus.AWAITING_CANCELLATION
            paymentDunning.cancellationReason = cancellationReason

            if (cancellationReason.isRequestedByAsaas()) {
                paymentDunning.denialReason = Utils.getMessageProperty("paymentDunning.cancel.REQUESTED_BY_ASAAS.denialReason")
            }

            paymentDunning.save()

            notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(paymentDunning)
            creditBureauDunningBatchItemService.saveRemovalItem(paymentDunning)
            paymentDunningStatusHistoryService.save(paymentDunning, description)
        } else {
            refundFeeAndCancel(paymentDunning, cancellationReason, description)
            creditBureauDunningBatchItemService.cancelNotTransmittedCreationItemIfExists(paymentDunning)
        }

        return paymentDunning
    }

    public void refundFeeAndCancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason) {
        refundFeeAndCancel(paymentDunning, cancellationReason, null)
    }

    public void refundFeeAndCancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason, String description) {
        if (paymentDunning.fee > 0) chargedFeeService.refundDunningRequestFee(paymentDunning)
        paymentDunningService.cancel(paymentDunning, cancellationReason, description)
    }

    public void onPaymentConfirm(PaymentDunning paymentDunning) {
        if (paymentDunning.status.isCancelled() || paymentDunning.status.isAwaitingCancellation()) return

        boletoBatchFileItemService.deleteRegistrationIfRegistered(paymentDunning.payment)

        if (paymentDunning.status.isAwaitingPartnerApproval() && !creditBureauDunningBatchItemService.existsCreationItemTransmittedWithoutReturn(paymentDunning)) {
            if (isLastCreationAttemptFail(paymentDunning)) {
                refundFeeAndCancel(paymentDunning, PaymentDunningCancellationReason.PAYMENT_CONFIRMED)
                return
            }
        }

        if (paymentDunning.status.isAwaitingPartnerApproval() || paymentDunning.status.isProcessed()) {
            paymentDunningService.setAsPaid(paymentDunning)
            creditBureauDunningBatchItemService.saveRemovalItem(paymentDunning)
            return
        }

        paymentDunning = cancel(paymentDunning, PaymentDunningCancellationReason.PAYMENT_CONFIRMED)
        if (paymentDunning.hasErrors()) throw new ValidationException("Erro no cancelamento da negativação da cobrança [${paymentDunning.payment.id}]", paymentDunning.errors)
    }

    public void confirmReceivedInCashIfNecessary(PaymentDunning paymentDunning) {
        if (paymentDunning.isCancelled()) return
        if (paymentDunning.status.isProcessingCancellation()) return

        paymentDunning = cancel(paymentDunning, PaymentDunningCancellationReason.RECEIVED_IN_CASH)
        if (paymentDunning.hasErrors()) {
            AsaasLogger.error("CreditBureauDunningService >> Não foi possível cancelar a negativação [${paymentDunning.id}] ao confirmar recebimento em dinheiro. Motivo: ${DomainUtils.getValidationMessagesAsString(paymentDunning.errors)}")
        }
    }

    public void onPartnerCancellation(PaymentDunning paymentDunning) {
        customerMessageService.sendPaymentDunningCancelledEmail(paymentDunning)
        deleteRegistrationIfExists(paymentDunning)
    }

    public void deleteRegistrationIfExists(PaymentDunning paymentDunning) {
        PaymentBankSlipInfo currentPaymentBankSlipInfo = PaymentBankSlipInfo.query([payment: paymentDunning.payment]).get()
        if (!currentPaymentBankSlipInfo) return

        paymentBankSlipInfoService.deleteAndSaveHistory(currentPaymentBankSlipInfo)
        boletoBatchFileItemService.delete(currentPaymentBankSlipInfo)
    }

    public void onPartnerApproval(PaymentDunning paymentDunning) {
        if (paymentDunning.payment.status.hasBeenConfirmed()) throw new BusinessException("Não é possível aprovar uma negativação quando o pagamento da cobrança já foi confirmado.")

        paymentDunning.payment.status = PaymentStatus.DUNNING_REQUESTED
        paymentDunning.payment.save(failOnError: true)

        paymentDunning.status = PaymentDunningStatus.PROCESSED
        paymentDunning.save(failOnError: true)

        customerMessageService.sendPaymentDunningProcessedEmail(paymentDunning)

        paymentPushNotificationRequestAsyncPreProcessingService.save(paymentDunning.payment, PushNotificationRequestEvent.PAYMENT_DUNNING_REQUESTED)

        asaasErpAccountingStatementService.onPaymentUpdate(paymentDunning.payment.provider, paymentDunning.payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentDunning(paymentDunning)

        paymentDunningStatusHistoryService.save(paymentDunning)

        receivableAnticipationBlocklistService.savePaymentOverdueIfNecessary(paymentDunning.payment.customerAccount, paymentDunning.payment.billingType)
    }

    public void onPartnerDenial(PaymentDunning paymentDunning, String denialReason) {
        paymentDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.DENIED_BY_PARTNER, denialReason)
        deleteRegistrationIfExists(paymentDunning)

        if (paymentDunning.fee > 0) chargedFeeService.refundDunningRequestFee(paymentDunning)
    }

    public void onAutomaticRemovedByPartner(PaymentDunning paymentDunning, String denialReason) {
        paymentDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.AUTOMATIC_REMOVED_BY_PARTNER, denialReason)
        deleteRegistrationIfExists(paymentDunning)
    }

    public void registerBankSlipIfNecessary(Long paymentId) {
        PaymentDunning paymentDunning = PaymentDunning.query(["paymentId": paymentId, type: PaymentDunningType.CREDIT_BUREAU, status: PaymentDunningStatus.PROCESSED]).get()
        if (!paymentDunning) return

        paymentDunning.lock()

        Utils.withNewTransactionAndRollbackOnError({
            Payment payment = Payment.get(paymentId)
            PaymentBankSlipInfo currentPaymentBankSlipInfo = PaymentBankSlipInfo.query([payment: payment]).get()
            if (!currentPaymentBankSlipInfo) return

            final Integer daysToAutomaticRegistrationCancellation = 60
            if (CustomDateUtils.sumDays(currentPaymentBankSlipInfo.dueDate, daysToAutomaticRegistrationCancellation) >= new Date().clearTime()) return

            paymentBankSlipInfoService.deleteAndSaveHistory(currentPaymentBankSlipInfo)

            saveAndRegister(payment, 30)
        }, [logErrorMessage: "CreditBureauDunningService >> Não foi possível registrar um novo boleto para a negativação da cobrança [${paymentId}]"])
    }

    public void saveAndRegister(Payment payment, Integer businessDayToAddToNewDueDate) {
        paymentBankSlipInfoService.save(payment, [dueDate: CustomDateUtils.addBusinessDays(new Date(), businessDayToAddToNewDueDate), applyFineAndInterest: false, applyDiscount: false, applyInstructions: false])

        BoletoBatchFileItem boletoBatchFileItem = boletoBatchFileItemService.create(payment)

        if (!eligibleForOnlineRegistration(payment)) return

        bankSlipOnlineRegistrationService.executeOnlineRegistration(payment, boletoBatchFileItem, [retryRegistration: true])
    }

    private Boolean eligibleForOnlineRegistration(Payment payment) {
        if (!payment.boletoBank?.onlineRegistrationEnabled) return false

        return true
    }

    private Boolean isLastCreationAttemptFail(PaymentDunning paymentDunning) {
        CreditBureauDunningReturnBatchItem lastReturnBatchItem = CreditBureauDunningReturnBatchItem.lastCreationItem([paymentDunning: paymentDunning]).get()
        if (!lastReturnBatchItem) return false
        if (!lastReturnBatchItem.errorCodes && lastReturnBatchItem.partnerChargedFeeValue) return false

        return true
    }
}
