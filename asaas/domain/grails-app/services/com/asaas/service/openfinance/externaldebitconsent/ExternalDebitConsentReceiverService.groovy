package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentReceiver
import com.asaas.openfinance.externaldebitconsent.adapter.base.children.ReceiverAccountAdapter

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentReceiverService {

    public ExternalDebitConsentReceiver save(ExternalDebitConsent consent, ReceiverAccountAdapter receiverAccountAdapter) {
        ExternalDebitConsentReceiver receiver = new ExternalDebitConsentReceiver()
        receiver.debitConsent = consent
        receiver.ispb = receiverAccountAdapter.ispb
        receiver.personType = receiverAccountAdapter.personType
        receiver.cpfCnpj = receiverAccountAdapter.cpfCnpj
        receiver.agency = receiverAccountAdapter.agency
        receiver.account = receiverAccountAdapter.account
        receiver.accountType = receiverAccountAdapter.accountType
        receiver.name = receiverAccountAdapter.name
        receiver.save(failOnError: true)
        return receiver
    }

}
