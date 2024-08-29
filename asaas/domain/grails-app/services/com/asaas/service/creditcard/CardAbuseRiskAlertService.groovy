package com.asaas.service.creditcard

import com.asaas.creditcard.CardAbuseRiskAlertStatus
import com.asaas.creditcard.CreditCardPreventAbuseVO
import com.asaas.creditcard.CreditCardTransactionAttemptType
import com.asaas.domain.creditcard.CardAbuseRiskAlert
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CardAbuseRiskAlertService {

    def messageService

    public void expireCardAbuseRiskAlertIfNecessary() {
        final Integer maxItemsToExpire = 500
        List<Long> cardAbuseRiskAlertIdList = CardAbuseRiskAlert.query([column: "id", "status": CardAbuseRiskAlertStatus.BLOCKED, "blockExpirationDate[lt]": new Date()]).list(max: maxItemsToExpire)
        if (!cardAbuseRiskAlertIdList) return

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(cardAbuseRiskAlertIdList, flushEvery, { Long cardAbuseRiskAlertId ->
            Utils.withNewTransactionAndRollbackOnError({
                CardAbuseRiskAlert cardAbuseRiskAlert = CardAbuseRiskAlert.get(cardAbuseRiskAlertId)
                cardAbuseRiskAlert.status = CardAbuseRiskAlertStatus.EXPIRED
                cardAbuseRiskAlert.save(failOnError: true)
            })
        })
    }

    public void saveIfNecessary(Customer customer, Boolean shouldBlock, Date blockExpirationDate) {
        CardAbuseRiskAlert cardAbuseRiskAlert = CardAbuseRiskAlert.query([customer: customer]).get()

        if (!blockExpirationDate) {
            if (isBlocked(cardAbuseRiskAlert)) return
            if (cardAbuseRiskAlert && cardAbuseRiskAlert.status.isWaitingAnalysis()) return
        }

        saveWithNewTransaction(customer, shouldBlock, blockExpirationDate)
    }

    public Boolean isBlocked(CardAbuseRiskAlert cardAbuseRiskAlert) {
        if (!cardAbuseRiskAlert) return false
        if (!cardAbuseRiskAlert.blockExpirationDate) return false

        return cardAbuseRiskAlert.blockExpirationDate >= new Date()
    }

    public void blockCustomerIfNecessary(Customer customer, CustomerAccount customerAccount, CreditCardPreventAbuseVO creditCardPreventAbuseVO, CreditCardTransactionAttemptType creditCardTransactionAttemptType) {
        if (!creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction) return

        if ([CreditCardTransactionAttemptType.TOKENIZATION, CreditCardTransactionAttemptType.TOKENIZED_CREDIT_CARD].contains(creditCardTransactionAttemptType)) {
            AsaasLogger.warn("CardAbuseRiskAlertService.shouldBlockCustomer >>> ${creditCardTransactionAttemptType.name()} deveria bloquear as transações de cartão [customer: ${customer.id} - customerAccount: ${customerAccount.id}].")
            creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction = false
        } else {
            customer.blockCreditCardTransaction()
            messageService.notifyCustomerCreditCardTransactionBlocked(customer)
        }

        saveIfNecessary(customer, creditCardPreventAbuseVO.blockCustomerToUseCreditCardTransaction, null)
    }

    private void saveWithNewTransaction(Customer customer, Boolean shouldBlock, Date blockDate) {
        Utils.withNewTransactionAndRollbackOnError( {
            CardAbuseRiskAlert cardAbuseRiskAlert = new CardAbuseRiskAlert()
            cardAbuseRiskAlert.customer = customer
            cardAbuseRiskAlert.status = shouldBlock ? CardAbuseRiskAlertStatus.BLOCKED : CardAbuseRiskAlertStatus.WAITING_ANALYSIS
            cardAbuseRiskAlert.analyzed = false
            cardAbuseRiskAlert.blockExpirationDate = cardAbuseRiskAlert.status.isBlocked() ? buildBlockExpirationDate(customer, blockDate) : null
            cardAbuseRiskAlert.save(failOnError: true)
        })
    }

    private Date buildBlockExpirationDate(Customer customer, Date blockDate) {
        if (blockDate) return blockDate

        Integer hoursToBlockCustomerWithCpfCnpj = 2
        if (customer.cpfCnpj) return CustomDateUtils.sumHours(new Date(), hoursToBlockCustomerWithCpfCnpj)

        Integer daysToBlockCustomerWithoutCpfCnpj = 2
        return CustomDateUtils.sumDays(new Date(), daysToBlockCustomerWithoutCpfCnpj)
    }
}
