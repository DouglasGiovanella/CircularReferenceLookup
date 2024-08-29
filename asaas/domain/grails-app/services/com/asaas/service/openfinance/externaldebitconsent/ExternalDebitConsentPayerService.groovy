package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentPayer
import com.asaas.openfinance.externaldebitconsent.adapter.base.children.PayerAccountAdapter

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentPayerService {

    public ExternalDebitConsentPayer save(ExternalDebitConsent consent, PayerAccountAdapter payerAdapter) {
        ExternalDebitConsentPayer payer = new ExternalDebitConsentPayer()
        payer.debitConsent = consent
        payer.cpf = payerAdapter.userCpf
        payer.cnpj = payerAdapter.cnpj
        payer.agency  = payerAdapter.agency
        payer.account = payerAdapter.account
        payer.accountDigit = payerAdapter.accountDigit
        payer.accountType = payerAdapter.accountType
        payer.accountNumber = payerAdapter.accountNumber
        payer.save(failOnError: true)
        return payer
    }

}
