package com.asaas.service.openfinance.externaldebit

import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebit.ExternalDebitPixInfo
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitAdapter

import grails.transaction.Transactional

@Transactional
class ExternalDebitPixInfoService {

    public ExternalDebitPixInfo save(ExternalDebit externalDebit, ExternalDebitAdapter externalDebitAdapter) {
        ExternalDebitPixInfo pixInfo = new ExternalDebitPixInfo()
        pixInfo.endToEndIdentifier = externalDebitAdapter.endToEndIdentifier
        pixInfo.externalDebit = externalDebit
        pixInfo.qrCodePayload = externalDebitAdapter.qrCodePayload
        pixInfo.pixKey = externalDebitAdapter.pixKey
        pixInfo.conciliationIdentifier = externalDebitAdapter.conciliationIdentifier
        pixInfo.originType = externalDebitAdapter.originType
        pixInfo.receiverIbgeCode = externalDebitAdapter.ibgeCode
        pixInfo.receiverIspb = externalDebitAdapter.receiver.ispb
        pixInfo.receiverAgency = externalDebitAdapter.receiver.agency
        pixInfo.receiverAccount = externalDebitAdapter.receiver.account
        pixInfo.receiverAccountType = externalDebitAdapter.receiver.accountType
        pixInfo.save(failOnError: true)
        return pixInfo
    }
}
