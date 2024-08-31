package com.asaas.service.paymentdunning

import com.asaas.domain.payment.PaymentDunning
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.payment.PaymentDunningStatus
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class DebtRecoveryAssistanceDunningService {

    def chargedFeeService
    def paymentDunningService

    public PaymentDunning cancel(PaymentDunning paymentDunning, PaymentDunningCancellationReason cancellationReason, Boolean chargeFeeIfNecessary) {
        if (!paymentDunning.type.isDebtRecoveryAssistance()) throw new Exception("Não é possível cancelar essa negativação pois não é uma Assessoria de cobrança.")

        BusinessValidation validatedBusiness = paymentDunning.canBeCancelled()
        if (!validatedBusiness.isValid()) {
            DomainUtils.addError(paymentDunning, validatedBusiness.asaasErrors[0].getMessage())
            return paymentDunning
        }

        if (paymentDunning.shouldChargeCancellationFee() && chargeFeeIfNecessary) {
            BigDecimal cancellationFeeValue = paymentDunning.getCancellationFeeValue()

            paymentDunningService.setFeeAndNetValue(paymentDunning, cancellationFeeValue)
            chargedFeeService.saveDunningCancellationFee(paymentDunning)
        }

        paymentDunningService.cancel(paymentDunning, cancellationReason)

        return paymentDunning
    }

    public PaymentDunning confirmReceivedInCash(PaymentDunning paymentDunning) {
        if (paymentDunning.shouldChargeReceivedInCashFee()) {
            paymentDunning.status = PaymentDunningStatus.CANCELLED
            paymentDunning.cancellationReason = PaymentDunningCancellationReason.RECEIVED_IN_CASH

            paymentDunningService.setFeeAndNetValue(paymentDunning, paymentDunning.getReceivedInCashFeeValue())
            paymentDunning.save(flush: true, failOnError: true)

            chargedFeeService.saveDunningReceivedInCashFee(paymentDunning)

            return paymentDunning
        } else {
            paymentDunningService.delete(paymentDunning.payment)
        }
    }
}
