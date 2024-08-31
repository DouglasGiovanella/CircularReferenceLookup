package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentRevocation
import com.asaas.domain.user.User
import com.asaas.openfinance.externaldebitconsent.enums.RevocationAgentType
import com.asaas.openfinance.externaldebitconsent.enums.RevocationOrigin
import com.asaas.openfinance.externaldebitconsent.enums.RevocationReasonType

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentRevocationService {

    public ExternalDebitConsentRevocation save(ExternalDebitConsent debitConsent, RevocationAgentType agentType, RevocationReasonType reasonType, String revocationReasonDescription, User user, RevocationOrigin revocationOrigin) {
        ExternalDebitConsentRevocation debitConsentRevocation = new ExternalDebitConsentRevocation()
        debitConsentRevocation.debitConsent = debitConsent
        debitConsentRevocation.agentType = agentType
        debitConsentRevocation.reasonType = reasonType
        debitConsentRevocation.revocationOrigin = revocationOrigin
        debitConsentRevocation.reasonDescription = revocationReasonDescription

        if (user) {
            debitConsentRevocation.user = user
        } else if (debitConsent.customer) {
            debitConsentRevocation.user = User.query([customer: debitConsent.customer]).get()
        }

        debitConsentRevocation.save(failOnError: true)

        return debitConsentRevocation
    }

    public ExternalDebitConsentRevocation save(ExternalDebit externalDebit, RevocationAgentType agentType, RevocationReasonType reasonType, String revocationReasonDescription, User user) {
        ExternalDebitConsentRevocation debitConsentRevocation = new ExternalDebitConsentRevocation()
        debitConsentRevocation.debitConsent = externalDebit.consent
        debitConsentRevocation.externalDebit = externalDebit
        debitConsentRevocation.agentType = agentType
        debitConsentRevocation.reasonType = reasonType
        debitConsentRevocation.reasonDescription = revocationReasonDescription
        debitConsentRevocation.user = user ?: User.query([customer: externalDebit.customer]).get()

        debitConsentRevocation.save(failOnError: true)

        return debitConsentRevocation
    }
}
