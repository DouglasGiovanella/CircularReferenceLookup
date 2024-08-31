package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentPixInfo
import com.asaas.openfinance.externaldebitconsent.adapter.children.PixDetailAdapter

import grails.transaction.Transactional

@Transactional
class ExternalDebitConsentPixInfoService {

    public ExternalDebitConsentPixInfo save(ExternalDebitConsent consent, PixDetailAdapter pixDetailAdapter) {
        ExternalDebitConsentPixInfo pixInfo = new ExternalDebitConsentPixInfo()
        pixInfo.debitConsent = consent
        pixInfo.qrCodePayload = pixDetailAdapter.qrCodePayload
        pixInfo.pixKey = pixDetailAdapter.pixKey
        pixInfo.ibgeCode = pixDetailAdapter.ibgeCode
        pixInfo.originType = pixDetailAdapter.originType
        pixInfo.save(failOnError: true)
        return pixInfo
    }
}
