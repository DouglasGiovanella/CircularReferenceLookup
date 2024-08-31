package com.asaas.service.asaascheckout

import com.asaas.asaascheckout.adapter.SaveCheckoutCallbackAdapter
import com.asaas.domain.asaascheckout.CheckoutCallback
import com.asaas.utils.DomainUtils
import com.asaas.utils.UriUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CheckoutCallbackService {

    public void save(SaveCheckoutCallbackAdapter adapter) {
        BusinessValidation businessValidation = validateSave(adapter)
        if (!businessValidation.isValid()) {
            CheckoutCallback checkoutCallback = DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, new CheckoutCallback())
            throw new ValidationException("Erro ao criar checkoutCallback", checkoutCallback.errors)
        }

        CheckoutCallback checkoutCallback = new CheckoutCallback()
        checkoutCallback.successUrl = adapter.successUrl
        checkoutCallback.cancelUrl = adapter.cancelUrl
        checkoutCallback.expiredUrl = adapter.expiredUrl
        checkoutCallback.save(failOnError: true)
    }

    private BusinessValidation validateSave(SaveCheckoutCallbackAdapter adapter) {
        BusinessValidation businessValidation = new BusinessValidation()
        String emptyStateErrorCode = "asaasCheckout.emptyState.message"
        String invalidValueErrorCode = "asaasCheckout.invalidValue.message"

        if (!adapter.successUrl) {
            businessValidation.addError(emptyStateErrorCode, ["successUrl"])
        } else if (!UriUtils.isValidUri(adapter.successUrl)) {
            businessValidation.addError(invalidValueErrorCode, ["successUrl"])
        }

        if (!adapter.cancelUrl) {
            businessValidation.addError(emptyStateErrorCode, ["cancelUrl"])
        } else if (!UriUtils.isValidUri(adapter.cancelUrl)) {
            businessValidation.addError(invalidValueErrorCode, ["cancelUrl"])
        }

        if (adapter.expiredUrl && !UriUtils.isValidUri(adapter.expiredUrl)) businessValidation.addError(invalidValueErrorCode, ["expiredUrl"])

        return businessValidation
    }
}
