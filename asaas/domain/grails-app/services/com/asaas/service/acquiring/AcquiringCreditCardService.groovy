package com.asaas.service.acquiring

import com.asaas.cardtransaction.CardTransactionResponseAdapter
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.creditcard.HolderInfo
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger

class AcquiringCreditCardService {

    def acquiringManagerService
    def creditCardAuthorizationInfoService
    def creditCardBlackListService

    public CardTransactionResponseAdapter authorize(CreditCardAcquirer acquirer, Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, billingInfo)
        creditCardTransactionVO.acquirer = acquirer
        creditCardTransactionVO.holderInfo = holderInfo
        creditCardTransactionVO.originInterface = originInterface

        CardTransactionResponseAdapter cardTransactionResponseAdapter = acquiringManagerService.authorize(creditCardTransactionVO, isFallback)

        return processTransactionResponse(cardTransactionResponseAdapter)
    }

    public CardTransactionResponseAdapter capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        return acquiringManagerService.capture(creditCardAuthorizationInfo, isFallback)
    }

    public CardTransactionResponseAdapter capture(CreditCardAcquirer acquirer, Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, HolderInfo holderInfo, CreditCardTransactionOriginInterface originInterface, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, billingInfo)
        creditCardTransactionVO.acquirer = acquirer
        creditCardTransactionVO.holderInfo = holderInfo
        creditCardTransactionVO.originInterface = originInterface

        CardTransactionResponseAdapter cardTransactionResponseAdapter = acquiringManagerService.capture(creditCardTransactionVO, isFallback)

        return processTransactionResponse(cardTransactionResponseAdapter)
    }

    public CardTransactionResponseAdapter refund(String transactionIdentifier) {
        CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionIdentifier]).get()

        if (!creditCardAuthorizationInfo) {
            AsaasLogger.error("AcquiringCreditCardService.refund >>> Erro ao estornar a transação: ${transactionIdentifier}: creditCardAuthorizationInfo não encontrado")
            return new CardTransactionResponseAdapter(false, null)
        }

        return refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, creditCardAuthorizationInfo.amountInCents)
    }

    public CardTransactionResponseAdapter refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, Long amountInCents) {
        return acquiringManagerService.refund(customer, customerAccount, transactionIdentifier, amountInCents)
    }

    private CardTransactionResponseAdapter processTransactionResponse(CardTransactionResponseAdapter cardTransactionResponseAdapter) {
        if (!cardTransactionResponseAdapter.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(
                cardTransactionResponseAdapter.acquirer,
                cardTransactionResponseAdapter.creditCard,
                cardTransactionResponseAdapter.billingInfo,
                cardTransactionResponseAdapter.message,
                cardTransactionResponseAdapter.acquirerReturnCode
            )

            cardTransactionResponseAdapter.blockedOnBlackList = blockedOnBlackList

            return cardTransactionResponseAdapter
        }

        creditCardAuthorizationInfoService.save(cardTransactionResponseAdapter)

        return cardTransactionResponseAdapter
    }
}
