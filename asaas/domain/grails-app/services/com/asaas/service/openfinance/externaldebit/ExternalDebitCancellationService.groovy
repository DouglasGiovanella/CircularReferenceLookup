package com.asaas.service.openfinance.externaldebit

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.openfinance.externaldebit.adapter.CancelExternalDebitAdapter
import com.asaas.openfinance.externaldebit.enums.ExternalDebitRefusalReason
import com.asaas.openfinance.externaldebit.enums.ExternalDebitStatus
import com.asaas.openfinance.externaldebitconsent.enums.RevocationAgentType
import com.asaas.openfinance.externaldebitconsent.enums.RevocationReasonType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ExternalDebitCancellationService {

    def externalDebitConsentRevocationService
    def externalDebitConsentService
    def externalDebitService

    public ExternalDebit cancelFromInitiator(CancelExternalDebitAdapter cancelExternalDebitAdapter) {
        ExternalDebit externalDebit = cancel(cancelExternalDebitAdapter.externalDebit, "Cancelado pela iniciadora")
        if (externalDebit.hasErrors()) return externalDebit

        if (externalDebit.consent.isSingleScheduled()) {
            ExternalDebitConsent externalDebitConsent = externalDebitConsentService.setAsRevoked(externalDebit.consent)
            if (externalDebitConsent.hasErrors()) throw new ValidationException("Não foi possível revogar o consentimento vinculado ao débito.", externalDebitConsent.errors)
        }

        externalDebitConsentRevocationService.save(cancelExternalDebitAdapter.externalDebit, RevocationAgentType.PAYMENT_INITIATOR, RevocationReasonType.OTHER, "Cancelado pela iniciadora", cancelExternalDebitAdapter.user)

        return externalDebit
    }

    public ExternalDebit cancel(ExternalDebit externalDebit, String reasonDescription) {
        ExternalDebit validatedExternalDebit = validateCancel(externalDebit)
        if (validatedExternalDebit.hasErrors()) return validatedExternalDebit

        ExternalDebitRefusalReason refusalReason = (externalDebit.status.isScheduled()) ? ExternalDebitRefusalReason.SCHEDULED_CANCELLED : ExternalDebitRefusalReason.PENDING_CANCELLED
        externalDebit = externalDebitService.cancel(externalDebit, refusalReason, reasonDescription)

        return externalDebit
    }

    public List<ExternalDebit> getExternalDebitCanBeCancelledList(ExternalDebitConsent externalDebitConsent) {
        List<ExternalDebit> externalDebitList = ExternalDebit.query([consent: externalDebitConsent, status: ExternalDebitStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST, ignoreCustomer : true]).list()
        externalDebitList += ExternalDebit.query([consent: externalDebitConsent, status: ExternalDebitStatus.SCHEDULED, "scheduledDate[gt]": new Date().clearTime(), ignoreCustomer : true]).list()

        return externalDebitList
    }

    private ExternalDebit validateCancel(ExternalDebit externalDebit) {
        if (!externalDebit.status.isStatusAllowsCancel()) {
            return DomainUtils.addErrorWithErrorCode(externalDebit, "openFinance.invalidCancel.invalidStatus", "O status do pagamento não permite cancelamento.")
        }

        Boolean scheduledExceededRevocationLimitDate = externalDebit.status.isScheduled() && CustomDateUtils.calculateDifferenceInDays(new Date(), externalDebit.scheduledDate) < ExternalDebitConsent.MINIMUM_DAYS_FOR_REVOCATION
        if (scheduledExceededRevocationLimitDate) {
            return DomainUtils.addErrorWithErrorCode(externalDebit, "openFinance.invalidCancel.reachedDeadline", "Este pagamento não poderá ser cancelado pois está fora do prazo.")
        }

        return externalDebit
    }
}
