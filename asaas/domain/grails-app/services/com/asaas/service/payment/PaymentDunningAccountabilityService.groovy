package com.asaas.service.payment

import com.asaas.domain.payment.PartialPayment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentDunningAccountability
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningAccountabilityStatus
import com.asaas.payment.PaymentDunningStatus
import com.asaas.payment.PaymentStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PaymentDunningAccountabilityService {

    def customerAlertNotificationService
    def partialPaymentService
    def paymentDunningStatusHistoryService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def asaasErpAccountingStatementService

    public void confirmPendingAccountabilities() {
        List<Long> pendingIds = PaymentDunningAccountability.pending([column: "id"]).list()

        for (Long accountabilityId : pendingIds) {
            confirm(accountabilityId)
        }
    }

    public Boolean confirm(Long accountabilityId) {
        def confirmResponse = Utils.withNewTransactionAndRollbackOnError({
            PaymentDunningAccountability accountability = PaymentDunningAccountability.get(accountabilityId)

            BusinessValidation validatedBusiness = accountability.canConfirm()
            if (!validatedBusiness.isValid()) return false

            if (accountability.type.isReceivedDirectly()) {
                confirmReceivedInCash(accountability)
                return true
            }

            if (accountability.type.isReceivedByPartner()) {
                PartialPayment partialPayment = confirmReceivedByPartner(accountability)
                return !partialPayment.hasErrors()
            }
            return false
        }, [onError: { Exception e ->
            AsaasLogger.error("PaymentDunningAccountabilityService.confirm >> Um erro ocorreu ao confirmar a prestação de contas ${accountabilityId}", e)
            }]
        )

        if (confirmResponse instanceof Exception) return false

        return confirmResponse
    }

    public PaymentDunningAccountability refuse(Long accountabilityId) {
        PaymentDunningAccountability accountability = PaymentDunningAccountability.get(accountabilityId)

        BusinessValidation validatedBusiness = accountability.canRefuse()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(accountability, validatedBusiness.asaasErrors[0].getMessage())
            return accountability
        }

        accountability.status = PaymentDunningAccountabilityStatus.REFUSED
        accountability.save(failOnError: true)
        return accountability
    }

    public Boolean dunningIsTotallyPaid(PaymentDunning dunning) {
        return (PaymentDunningAccountability.sumNetValue([dunning: dunning, status: PaymentDunningAccountabilityStatus.CONFIRMED]).get() >= dunning.netValue)
    }

    private void confirmReceivedInCash(PaymentDunningAccountability accountability) {
        setPaymentDunningAccountabilityStatusAsConfirmed(accountability)
    }

    private PartialPayment confirmReceivedByPartner(PaymentDunningAccountability accountability) {
        PartialPayment partialPayment = partialPaymentService.save(accountability)

        if (partialPayment.hasErrors()) return partialPayment

        setPaymentDunningAccountabilityStatusAsConfirmed(accountability)

        if (dunningIsTotallyPaid(accountability.dunning)) {
            setDunningStatusAsPaid(accountability.dunning)
        } else {
            setDunningStatusAsPartiallyPaid(accountability.dunning)
        }

        return partialPayment
    }

    private void setPaymentDunningAccountabilityStatusAsConfirmed(PaymentDunningAccountability accountability) {
        accountability.status = PaymentDunningAccountabilityStatus.CONFIRMED
        accountability.confirmedDate = new Date().clearTime()
        accountability.save(failOnError: true, flush: true)
    }

    private setDunningStatusAsPartiallyPaid(PaymentDunning dunning) {
        dunning.status = PaymentDunningStatus.PARTIALLY_PAID
        dunning.save(failOnError: true)

        paymentDunningStatusHistoryService.save(dunning)
    }

    private setDunningStatusAsPaid(PaymentDunning dunning) {
        dunning.status = PaymentDunningStatus.PAID
        dunning.save(failOnError: true)

        setPaymentStatusAsDunningReceived(dunning.payment)
        customerAlertNotificationService.notifyDunningPaid(dunning.payment.provider, dunning.id)
        paymentPushNotificationRequestAsyncPreProcessingService.save(dunning.payment, PushNotificationRequestEvent.PAYMENT_DUNNING_RECEIVED)
        asaasErpAccountingStatementService.onPaymentUpdate(dunning.payment.provider, dunning.payment, null, true)

        paymentDunningStatusHistoryService.save(dunning)
    }

    private setPaymentStatusAsDunningReceived(Payment payment) {
        payment.status = PaymentStatus.DUNNING_RECEIVED
        payment.save(failOnError: true)
    }
}
