package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.user.User
import com.asaas.openfinance.externaldebit.adapter.CancelExternalDebitAdapter
import com.asaas.openfinance.externaldebitconsent.adapter.ExternalDebitConsentRevocationAdapter
import com.asaas.openfinance.externaldebitconsent.enums.RevocationAgentType
import com.asaas.openfinance.externaldebitconsent.enums.RevocationOrigin
import com.asaas.openfinance.externaldebitconsent.enums.RevocationReasonType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ExternalDebitConsentCancellationService {

    def externalDebitCancellationService
    def externalDebitConsentService
    def externalDebitConsentRevocationService
    def pixTransactionService

    public ExternalDebitConsent cancelFromAccountHolder(ExternalDebitConsent externalDebitConsent, User user, String revocationReasonDescription) {
        if (externalDebitConsent.canBeRevoked().isValid()) {
            if (!revocationReasonDescription) revocationReasonDescription = "Cancelado pelo usuário no Asaas sem especificar motivo."

            externalDebitConsent = revoke(externalDebitConsent, revocationReasonDescription)
            externalDebitConsentRevocationService.save(externalDebitConsent, RevocationAgentType.USER, RevocationReasonType.OTHER, revocationReasonDescription, user, RevocationOrigin.ACCOUNT_HOLDER)

            return externalDebitConsent
        }

        return externalDebitConsentService.reject(externalDebitConsent)
    }

    public ExternalDebitConsent revokeFromPaymentTransactionInitiator(ExternalDebitConsentRevocationAdapter consentRevocationAdapter) {
        consentRevocationAdapter.debitConsent = revoke(consentRevocationAdapter.debitConsent, Utils.getMessageProperty("openFinance.reasonDescription.cancelFromInitiator"))
        externalDebitConsentRevocationService.save(consentRevocationAdapter.debitConsent, RevocationAgentType.PAYMENT_INITIATOR, RevocationReasonType.OTHER, Utils.getMessageProperty("openFinance.reasonDescription.cancelFromInitiator"), null, RevocationOrigin.PAYMENT_INITIATOR)

        return consentRevocationAdapter.debitConsent
    }

    public ExternalDebitConsent revokeFromPaymentTransactionInitiator(CancelExternalDebitAdapter cancelExternalDebitAdapter) {
        ExternalDebitConsent externalDebitConsent = cancelExternalDebitAdapter.externalDebit.consent
        externalDebitConsent = revoke(externalDebitConsent, "Cancelado pela iniciadora")
        if (externalDebitConsent.hasErrors()) return externalDebitConsent

        externalDebitConsentRevocationService.save(externalDebitConsent, RevocationAgentType.PAYMENT_INITIATOR, RevocationReasonType.OTHER, "Cancelado pela iniciadora", cancelExternalDebitAdapter.user, RevocationOrigin.PAYMENT_INITIATOR)

        return externalDebitConsent
    }

    private ExternalDebitConsent revoke(ExternalDebitConsent debitConsent, String reasonDescription) {
        debitConsent = externalDebitConsentService.setAsRevoked(debitConsent)
        if (debitConsent.hasErrors()) throw new ValidationException("Não foi possível revogar o consentimento.", debitConsent.errors)

        List<ExternalDebit> externalDebitList = externalDebitCancellationService.getExternalDebitCanBeCancelledList(debitConsent)
        if (!externalDebitList) return DomainUtils.addErrorWithErrorCode(debitConsent, "openFinance.invalidCancel.noExternalDebitCancellableFounded", "Nenhum pagamento passível de cancelamento encontrado.")

        for (ExternalDebit externalDebit : externalDebitList) {
            externalDebit = externalDebitCancellationService.cancel(externalDebit, reasonDescription)
            if (debitConsent.hasErrors()) return DomainUtils.addErrorWithErrorCode(debitConsent, debitConsent.errors.allErrors.first().getCodes()[0], DomainUtils.getFirstValidationMessage(externalDebit))
        }

       return debitConsent
    }
}
