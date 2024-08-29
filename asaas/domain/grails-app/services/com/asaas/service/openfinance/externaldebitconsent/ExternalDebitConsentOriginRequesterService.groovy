package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentOriginRequester
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentOriginRequesterApiName
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentOriginRequesterCreatedVersion

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentOriginRequesterService {

    public ExternalDebitConsentOriginRequester save(ExternalDebitConsent externalDebitConsent, ExternalDebitConsentOriginRequesterCreatedVersion createdVersion, ExternalDebitConsentOriginRequesterApiName apiName) {
        ExternalDebitConsentOriginRequester originRequester = new ExternalDebitConsentOriginRequester()
        originRequester.externalDebitConsent = externalDebitConsent
        originRequester.createdVersion = createdVersion
        originRequester.apiName = apiName
        originRequester.save(failOnError: true)

        return originRequester
    }
}
