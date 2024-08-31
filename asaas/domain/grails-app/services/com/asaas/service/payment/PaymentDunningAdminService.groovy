package com.asaas.service.payment

import com.asaas.domain.payment.PaymentDunning
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PaymentDunningAdminService {

    def creditBureauDunningService

    public PaymentDunning cancel(PaymentDunning paymentDunning, String description) {
        PaymentDunning validatedDomain = validateCancel(paymentDunning, description)
        if (validatedDomain.hasErrors()) return validatedDomain

        return creditBureauDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.REQUESTED_BY_ASAAS, description)
    }

    private PaymentDunning validateCancel(PaymentDunning paymentDunning, String cancelReason) {
        PaymentDunning validationDomain = new PaymentDunning()

        if (!cancelReason) {
            DomainUtils.addError(validationDomain, "O motivo do cancelamento é obrigatório")
        }

        BusinessValidation validatedBusiness = paymentDunning.canBeCancelled()
        DomainUtils.copyAllErrorsFromBusinessValidation(validatedBusiness, validationDomain)

        return validationDomain
    }
}
