package com.asaas.service.creditbureaureport

import com.asaas.domain.creditbureaureport.CreditBureauReportAgreement
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User

import grails.transaction.Transactional

@Transactional
class CreditBureauReportAgreementService {

    public CreditBureauReportAgreement save(Customer customer, User user, String remoteIp, String userAgent, String headers, String terms) {
        Map agreementFields = [
            remoteIp: remoteIp,
            customer: customer,
            user: user,
            userAgent: userAgent,
            terms: terms,
            requestHeaders: headers,
            contractVersion: CreditBureauReportAgreement.getCurrentContractVersion()
        ]

        CreditBureauReportAgreement creditBureauReportAgreement = new CreditBureauReportAgreement(agreementFields)
        creditBureauReportAgreement.save(failOnError: false)

        return creditBureauReportAgreement
    }
}
