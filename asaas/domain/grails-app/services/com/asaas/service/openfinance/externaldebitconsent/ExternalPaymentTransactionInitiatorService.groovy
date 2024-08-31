package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.ExternalPaymentTransactionInitiator
import com.asaas.openfinance.externaldebitconsent.adapter.base.BaseExternalDebitConsentAdapter
import grails.transaction.Transactional

@Transactional
class ExternalPaymentTransactionInitiatorService {

    public ExternalPaymentTransactionInitiator createOrUpdate(BaseExternalDebitConsentAdapter consentAdapter) {
        ExternalPaymentTransactionInitiator externalPaymentTransactionInitiator = ExternalPaymentTransactionInitiator.query([clientId: consentAdapter.externalInitiatorId]).get()

        if (!externalPaymentTransactionInitiator) {
            externalPaymentTransactionInitiator = new ExternalPaymentTransactionInitiator()
            externalPaymentTransactionInitiator.clientId = consentAdapter.externalInitiatorId
            externalPaymentTransactionInitiator.ispb = consentAdapter.ispbInitiator
            externalPaymentTransactionInitiator.webhookUri = consentAdapter.webhookUri
        } else {
            externalPaymentTransactionInitiator.webhookUri = consentAdapter.webhookUri
        }

        externalPaymentTransactionInitiator.save(failOnError: true)

        return externalPaymentTransactionInitiator
    }
}
