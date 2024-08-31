package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationSchedulingService {

    def receivableAnticipationCancellationService
    def receivableAnticipationService

    public void handlePaymentConfirmation(Payment payment) {
        ReceivableAnticipation scheduledAnticipation = ReceivableAnticipation.scheduled([payment: payment]).get()
        if (!scheduledAnticipation) return

        handleScheduledAnticipation(scheduledAnticipation)
    }

    public void handleInstallmentConfirmation(Installment installment) {
        List<ReceivableAnticipation> scheduledAnticipationList = ReceivableAnticipation.scheduled(["payment.installment": installment]).list()
        ReceivableAnticipation scheduledInstallmentAnticipation = ReceivableAnticipation.scheduled([installment: installment]).get()
        if (scheduledInstallmentAnticipation) scheduledAnticipationList.add(scheduledInstallmentAnticipation)

        for (ReceivableAnticipation scheduledAnticipation : scheduledAnticipationList) {
            handleScheduledAnticipation(scheduledAnticipation)
        }
    }

    public ReceivableAnticipation cancelScheduled(ReceivableAnticipation anticipation, ReceivableAnticipationCancelReason cancelReason) {
        if (!anticipation.status.isScheduled()) throw new RuntimeException("A antecipação [${anticipation.id}] não pode ser cancelada.")

        receivableAnticipationCancellationService.cancel(anticipation, cancelReason)

        return anticipation
    }

    public void handleScheduledAnticipation(ReceivableAnticipation scheduledAnticipation) {
        if (receivableAnticipationService.willAnyPaymentBeAffectedByContractualEffect(scheduledAnticipation)) {
            cancelScheduled(scheduledAnticipation, ReceivableAnticipationCancelReason.PAYMENT_AFFECTED_BY_CONTRACTUAL_EFFECT)
            return
        }
        if (scheduledAnticipation.scheduleDaysAfterConfirmation) {
            receivableAnticipationService.recalculateFee(scheduledAnticipation, [schedule: true])
            return
        }
        executeIfScheduled(scheduledAnticipation.installment ?: scheduledAnticipation.payment)
    }

    public Boolean processScheduledBankSlipAnticipations() {
        final Integer flushEvery = 50
        Boolean hasError = false

        List<Long> anticipationIdList = ReceivableAnticipation.scheduled([column: "id", billingType: BillingType.BOLETO, anticipationDateFinish: new Date().clearTime(), disableSort: true]).list()

        Utils.forEachWithFlushSession(anticipationIdList, flushEvery, { Long anticipationId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)

                receivableAnticipationService.setAsPendingAndUpdateValueAndFee(anticipation)
            }, [logErrorMessage: "ReceivableAnticipationSchedulingService.processScheduledBankSlipAnticipations >> Erro ao processar a antecipação [${anticipationId}]",
                onError: { hasError = true }])
        })

        return hasError
    }

    public Boolean processScheduledCreditCardAnticipations() {
        final Integer flushEvery = 50
        Boolean hasError = false

        List<Long> anticipationIdList = ReceivableAnticipation.scheduled([column: "id", billingType: BillingType.MUNDIPAGG_CIELO, "scheduleDaysAfterConfirmation[gt]": 0, anticipationDateFinish: new Date().clearTime(), disableSort: true]).list()

        Utils.forEachWithFlushSession(anticipationIdList, flushEvery, { Long anticipationId ->
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = ReceivableAnticipation.get(anticipationId)

                Boolean isConfirmed = anticipation.payment ? anticipation.payment.isConfirmed() : anticipation.installment.getFirstConfirmedPayment().asBoolean()
                if (isConfirmed) {
                    Date settlementDate = anticipation.payment ? anticipation.payment.creditDate : anticipation.installment.getFirstConfirmedPayment().creditDate
                    Boolean anticipationWillBeAffected = receivableAnticipationService.willAnyPaymentBeAffectedByContractualEffect(anticipation)

                    if (anticipationWillBeAffected) {
                        receivableAnticipationCancellationService.cancel(anticipation, ReceivableAnticipationCancelReason.PAYMENT_AFFECTED_BY_CONTRACTUAL_EFFECT)
                        return
                    }

                    if (anticipation.anticipationDate > settlementDate) {
                        receivableAnticipationCancellationService.cancel(anticipation, ReceivableAnticipationCancelReason.ANTICIPATION_DATE_AFTER_PAYMENT_CREDIT_DATE)
                        return
                    }

                    receivableAnticipationService.setAsPendingAndUpdateValueAndFee(anticipation)
                    receivableAnticipationService.autoApproveCreditCardAnticipationIfEnabled(anticipation)
                } else {
                    receivableAnticipationService.recalculateFee(anticipation, [schedule: true])
                }
            }, [logErrorMessage: "ReceivableAnticipationSchedulingService.processScheduledCreditCardAnticipations >> Erro ao processar a antecipação [${anticipationId}]",
                onError: { hasError = true }])
        })

        return hasError
    }

    private void executeIfScheduled(Installment installment) {
        ReceivableAnticipation scheduledAnticipation = ReceivableAnticipation.scheduled([installment: installment]).get()
        if (!scheduledAnticipation) return

        receivableAnticipationService.setAsPendingAndUpdateValueAndFee(scheduledAnticipation)

        receivableAnticipationService.autoApproveCreditCardAnticipationIfEnabled(scheduledAnticipation)
    }

    private void executeIfScheduled(Payment payment) {
        ReceivableAnticipation scheduledAnticipation = ReceivableAnticipation.scheduled([payment: payment]).get()
        if (!scheduledAnticipation) return

        receivableAnticipationService.setAsPendingAndUpdateValueAndFee(scheduledAnticipation)

        receivableAnticipationService.autoApproveCreditCardAnticipationIfEnabled(scheduledAnticipation)
    }
}
