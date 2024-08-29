package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.PaymentDunningAgreement
import com.asaas.domain.user.User
import com.asaas.paymentdunning.PaymentDunningType
import grails.transaction.Transactional

@Transactional
class PaymentDunningAgreementService {

    public PaymentDunningAgreement save(Customer customer, User user, Map params) {
        Map agreementFields = [
            remoteIp: params.remoteIp,
            customer: customer,
            user: user,
            userAgent: params.userAgent,
            terms: PaymentDunningAgreement.getCurrentContractText(),
            requestHeaders: params.headers,
            paymentDunningType: PaymentDunningType.CREDIT_BUREAU,
            contractVersion: PaymentDunningAgreement.getCurrentContractVersion()
        ]

        PaymentDunningAgreement dunningAgreement = new PaymentDunningAgreement(agreementFields)
        dunningAgreement.save(failOnError: true)

        return dunningAgreement
    }
}