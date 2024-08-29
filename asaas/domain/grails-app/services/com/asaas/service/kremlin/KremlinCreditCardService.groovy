package com.asaas.service.kremlin

import com.asaas.creditcard.CreditCard
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.integration.kremlin.adapter.TokenizationInfoAdapter
import com.asaas.integration.kremlin.adapter.TokenizedCreditCardInfoAdapter
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class KremlinCreditCardService {

    def asyncActionService
    def adyenCreditCardService
    def kremlinTokenizationManagerService
    def redeCreditCardService

    public Map tokenizeValidatedCreditCard(CustomerAccount customerAccount, CreditCard creditCard, Payment payment, Boolean isFallback) {
        Map validationResult = validateCreditCardInfo(customerAccount, creditCard, isFallback)

        if (!validationResult.success) return validationResult

        TokenizationInfoAdapter tokenizationInfoAdapter = tokenize(customerAccount, creditCard, payment, isFallback)

        if (!validationResult.creditCardTransactionLogIdList) validationResult.creditCardTransactionLogIdList = []

        validationResult.creditCardTransactionLogIdList.add(tokenizationInfoAdapter.creditCardTransactionLogId)

        return validationResult + [success: tokenizationInfoAdapter.tokenized, instantBuyKey: tokenizationInfoAdapter.token, customerToken: customerAccount.id, billingInfoPublicId: tokenizationInfoAdapter.billingInfoPublicId]
    }

    public TokenizationInfoAdapter tokenize(CustomerAccount customerAccount, CreditCard creditCard, Payment payment, Boolean isFallback) {
        return kremlinTokenizationManagerService.tokenize(creditCard, customerAccount, payment, isFallback)
    }

    public CreditCard getCreditCardInfo(BillingInfo billingInfo) {
        TokenizedCreditCardInfoAdapter tokenizedCreditCardInfoAdapter = kremlinTokenizationManagerService.getCreditCardInfo(billingInfo)

        if (!tokenizedCreditCardInfoAdapter.success) return null

        return tokenizedCreditCardInfoAdapter.creditCard
    }

    public void deleteTokenizedCreditCard(String token, String billingInfoPublicId) {
        Utils.withNewTransactionAndRollbackOnError ( {
            asyncActionService.saveDeleteTokenizedCreditCardKremlin(token, billingInfoPublicId)
        }, [logErrorMessage: "KremlinAsyncActionService.deleteTokenizedCreditCard >>> Erro ao adicionar à fila ação para deletar o cartão tokenizado no KREMLIN [billingInfoPublicId: ${billingInfoPublicId}]."] )
    }

    public void processQueueToDeleteTokenizedCreditCard() {
        Integer maxItems = 200

        List<Map> deleteTokenizedCreditCardKremlinList = asyncActionService.listDeleteTokenizedCreditCardKremlin(maxItems)

        Utils.forEachWithFlushSession(deleteTokenizedCreditCardKremlinList, 50, { Map asyncActionDataMap ->
            Utils.withNewTransactionAndRollbackOnError ( {
                kremlinTokenizationManagerService.deleteTokenizedCreditCard(asyncActionDataMap.token, asyncActionDataMap.billingInfoPublicId)
                asyncActionService.delete(asyncActionDataMap.asyncActionId)
            },
                [
                    logErrorMessage: "KremlinAsyncActionService.deleteTokenizedCreditCard >>> Erro ao deletar o cartão tokenizado [billingInfoPublicId: ${asyncActionDataMap.billingInfoPublicId}].",
                    onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionDataMap.asyncActionId) }
                ]
            )
        })
    }

    private Map validateCreditCardInfo(CustomerAccount customerAccount, CreditCard creditCard, Boolean isFallback) {
        creditCard.buildBrand()

        Map validationResult = [success: false]

        if (creditCard.brand.isRedeApplicable()) validationResult = redeCreditCardService.validateCreditCardInfo(customerAccount, creditCard, isFallback)

        if (validationResult.success) return validationResult

        return adyenCreditCardService.getInstantBuyKey(creditCard, customerAccount, isFallback)
    }
}
