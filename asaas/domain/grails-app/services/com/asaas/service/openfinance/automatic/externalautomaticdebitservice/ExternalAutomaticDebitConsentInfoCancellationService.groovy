package com.asaas.service.openfinance.automatic.externalautomaticdebitservice

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.openfinance.automatic.externalautomaticdebitconsent.adapter.CancelExternalAutomaticDebitConsentAdapter
import com.asaas.openfinance.externaldebitconsent.enums.RevocationAgentType
import com.asaas.openfinance.externaldebitconsent.enums.RevocationOrigin
import com.asaas.openfinance.externaldebitconsent.enums.RevocationReasonType
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class ExternalAutomaticDebitConsentInfoCancellationService {

    def externalDebitConsentRevocationService
    def externalDebitConsentService

    public ExternalAutomaticDebitConsentInfo cancelFromPaymentInitiator(CancelExternalAutomaticDebitConsentAdapter externalAutomaticDebitConsentRevocationAdapter) {
        ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo = externalAutomaticDebitConsentRevocationAdapter.externalAutomaticDebitConsentInfo

        if (externalAutomaticDebitConsentRevocationAdapter.rejectionInfo) return rejectFromPaymentInitiator(externalAutomaticDebitConsentInfo, externalAutomaticDebitConsentRevocationAdapter.rejectionInfo.reasonDetail)
        if (externalAutomaticDebitConsentRevocationAdapter.revocationInfo) return revokeFromPaymentInitiator(externalAutomaticDebitConsentInfo, externalAutomaticDebitConsentRevocationAdapter.revocationInfo.reasonType, externalAutomaticDebitConsentRevocationAdapter.revocationInfo.reasonDetail)
    }

    private ExternalAutomaticDebitConsentInfo rejectFromPaymentInitiator(ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo, String rejectReasonDescription) {
        if (!externalAutomaticDebitConsentInfo.consent.status.canBeRejectedByPaymentInitiator()) {
            DomainUtils.addErrorWithErrorCode(externalAutomaticDebitConsentInfo, "openFinance.invalidCancelConsent.invalidStatus", "O status do consentimento não permite a realização do cancelamento.")
            return externalAutomaticDebitConsentInfo
        }

        externalAutomaticDebitConsentInfo = reject(externalAutomaticDebitConsentInfo)
        if (externalAutomaticDebitConsentInfo.hasErrors()) return externalAutomaticDebitConsentInfo

        externalDebitConsentRevocationService.save(externalAutomaticDebitConsentInfo.consent, RevocationAgentType.USER, RevocationReasonType.OTHER, rejectReasonDescription, null, RevocationOrigin.PAYMENT_INITIATOR)
        return externalAutomaticDebitConsentInfo
    }

    private ExternalAutomaticDebitConsentInfo reject(ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo) {
        ExternalDebitConsent externalDebitConsent = externalAutomaticDebitConsentInfo.consent

        externalDebitConsent = externalDebitConsentService.reject(externalDebitConsent)
        if (externalDebitConsent.hasErrors()) return DomainUtils.copyAllErrorsWithErrorCodeFromObject(externalDebitConsent, externalAutomaticDebitConsentInfo) as ExternalAutomaticDebitConsentInfo

        return externalAutomaticDebitConsentInfo
    }

    private ExternalAutomaticDebitConsentInfo revokeFromPaymentInitiator(ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo, RevocationReasonType revocationReasonType, String rejectReasonDescription) {
        if (!externalAutomaticDebitConsentInfo.consent.status.canBeRevokedByPaymentInitiator()) {
            DomainUtils.addErrorWithErrorCode(externalAutomaticDebitConsentInfo, "openFinance.invalidCancelConsent.invalidStatus", "O status do consentimento não permite a realização do cancelamento.")
            return externalAutomaticDebitConsentInfo
        }

        externalAutomaticDebitConsentInfo = revoke(externalAutomaticDebitConsentInfo)
        if (externalAutomaticDebitConsentInfo.hasErrors()) return externalAutomaticDebitConsentInfo

        externalDebitConsentRevocationService.save(externalAutomaticDebitConsentInfo.consent, RevocationAgentType.USER, revocationReasonType, rejectReasonDescription, null, RevocationOrigin.PAYMENT_INITIATOR)
        return externalAutomaticDebitConsentInfo
    }

    private ExternalAutomaticDebitConsentInfo revoke(ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo) {
        ExternalDebitConsent externalDebitConsent = externalAutomaticDebitConsentInfo.consent

        externalDebitConsent = externalDebitConsentService.setAsRevoked(externalDebitConsent)
        if (externalDebitConsent.hasErrors()) return DomainUtils.copyAllErrorsWithErrorCodeFromObject(externalDebitConsent, externalAutomaticDebitConsentInfo) as ExternalAutomaticDebitConsentInfo

        return externalAutomaticDebitConsentInfo
    }
}
