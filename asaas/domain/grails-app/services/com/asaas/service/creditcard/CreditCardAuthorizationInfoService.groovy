package com.asaas.service.creditcard

import com.asaas.cardtransaction.CardTransactionResponseAdapter
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.adapter.CreditCardAuthorizationInfoAdapter
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import grails.transaction.Transactional

@Transactional
class CreditCardAuthorizationInfoService {

    public CreditCardAuthorizationInfo save(Payment payment, Installment installment, String transactionReference, String transactionIdentifier, Long amountInCents, BillingInfo billingInfo, CreditCard creditCard, CreditCardGateway gateway, String authorizationCode) {
        CreditCardAuthorizationInfo creditCardAuthorizationInfo = new CreditCardAuthorizationInfo()
        creditCardAuthorizationInfo.installment = installment
        if (payment?.id) creditCardAuthorizationInfo.payment = payment
        creditCardAuthorizationInfo.transactionReference = transactionReference
        creditCardAuthorizationInfo.transactionIdentifier = transactionIdentifier
        creditCardAuthorizationInfo.amountInCents = amountInCents
        creditCardAuthorizationInfo.gateway = gateway
        creditCardAuthorizationInfo.authorizationCode = authorizationCode

        if (billingInfo) {
            creditCardAuthorizationInfo.billingInfoId = billingInfo.id
            creditCardAuthorizationInfo.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            creditCardAuthorizationInfo.billingInfoId = creditCard.billingInfo?.id
            creditCardAuthorizationInfo.creditCardBin = creditCard.buildBin()
        }

        return creditCardAuthorizationInfo.save(failOnError: true)
    }

    public CreditCardAuthorizationInfo save(CardTransactionResponseAdapter cardTransactionResponseAdapter) {
        return save(
            cardTransactionResponseAdapter.payment,
            cardTransactionResponseAdapter.installment,
            cardTransactionResponseAdapter.transactionReference,
            cardTransactionResponseAdapter.transactionIdentifier,
            cardTransactionResponseAdapter.amountInCents,
            cardTransactionResponseAdapter.billingInfo,
            cardTransactionResponseAdapter.creditCard,
            cardTransactionResponseAdapter.gateway,
            cardTransactionResponseAdapter.authorizationCode
        )
    }

    public CreditCardAuthorizationInfo update(CreditCardAuthorizationInfo creditCardAuthorizationInfo, CreditCardAuthorizationInfoAdapter cardAuthorizationInfoAdapter) {
        if (cardAuthorizationInfoAdapter.installment) creditCardAuthorizationInfo.installment = cardAuthorizationInfoAdapter.installment
        if (cardAuthorizationInfoAdapter.payment) creditCardAuthorizationInfo.payment = cardAuthorizationInfoAdapter.payment
        if (cardAuthorizationInfoAdapter.requestKey) creditCardAuthorizationInfo.requestKey = cardAuthorizationInfoAdapter.requestKey
        if (cardAuthorizationInfoAdapter.transactionKey) creditCardAuthorizationInfo.transactionKey = cardAuthorizationInfoAdapter.transactionKey
        if (cardAuthorizationInfoAdapter.transactionReference) creditCardAuthorizationInfo.transactionReference = cardAuthorizationInfoAdapter.transactionReference
        if (cardAuthorizationInfoAdapter.orderKey) creditCardAuthorizationInfo.orderKey = cardAuthorizationInfoAdapter.orderKey
        if (cardAuthorizationInfoAdapter.transactionIdentifier) creditCardAuthorizationInfo.transactionIdentifier = cardAuthorizationInfoAdapter.transactionIdentifier
        if (cardAuthorizationInfoAdapter.amountInCents) creditCardAuthorizationInfo.amountInCents = cardAuthorizationInfoAdapter.amountInCents
        if (cardAuthorizationInfoAdapter.billingInfoId) creditCardAuthorizationInfo.billingInfoId = cardAuthorizationInfoAdapter.billingInfoId
        if (cardAuthorizationInfoAdapter.creditCardBin) creditCardAuthorizationInfo.creditCardBin = cardAuthorizationInfoAdapter.creditCardBin
        if (cardAuthorizationInfoAdapter.gateway) creditCardAuthorizationInfo.gateway = cardAuthorizationInfoAdapter.gateway
        if (cardAuthorizationInfoAdapter.authorizationCode) creditCardAuthorizationInfo.authorizationCode = cardAuthorizationInfoAdapter.authorizationCode

        return creditCardAuthorizationInfo.save(failOnError: true)
    }
}
