package com.asaas.service.cielo

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.creditcard.CreditCardUtils
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.utils.MoneyUtils
import grails.transaction.Transactional

@Transactional
class CieloCreditCardService {

    def cieloManagerService
    def creditCardBlackListService

    public Map capture(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, billingInfo)

        Map result = cieloManagerService.capture(creditCardTransactionVO, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, billingInfo, result.message, result.returnCode)
            result.blockedOnBlackList = blockedOnBlackList

            return result
        }

        CreditCardAuthorizationInfo creditCardAuthorizationInfo = new CreditCardAuthorizationInfo()
        creditCardAuthorizationInfo.installment = installment
        creditCardAuthorizationInfo.payment = payment
        creditCardAuthorizationInfo.transactionReference = result.transactionReference
        creditCardAuthorizationInfo.transactionIdentifier = result.transactionIdentifier
        creditCardAuthorizationInfo.amountInCents = result.amountInCents
        creditCardAuthorizationInfo.gateway = result.gateway
        creditCardAuthorizationInfo.authorizationCode = result.authorizationCode

        if (billingInfo) {
            creditCardAuthorizationInfo.billingInfoId = billingInfo.id
            creditCardAuthorizationInfo.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            creditCardAuthorizationInfo.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
            creditCardAuthorizationInfo.creditCardBin = creditCard.buildBin()
        }

        creditCardAuthorizationInfo.save(failOnError: true)

        return result
    }

    public Map authorize(Installment installment, Payment payment, CreditCard creditCard, BillingInfo billingInfo, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, billingInfo)

        Map result = cieloManagerService.authorize(creditCardTransactionVO, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, billingInfo, result.message, result.returnCode)
            result.blockedOnBlackList = blockedOnBlackList

            return result
        }

        CreditCardAuthorizationInfo creditCardAuthorizationInfo = new CreditCardAuthorizationInfo()
        creditCardAuthorizationInfo.installment = installment
        creditCardAuthorizationInfo.payment = payment
        creditCardAuthorizationInfo.transactionReference = result.transactionReference
        creditCardAuthorizationInfo.transactionIdentifier = result.transactionIdentifier
        creditCardAuthorizationInfo.amountInCents = result.amountInCents
        creditCardAuthorizationInfo.gateway = result.gateway
        creditCardAuthorizationInfo.authorizationCode = result.authorizationCode

        if (billingInfo) {
            creditCardAuthorizationInfo.billingInfoId = billingInfo.id
            creditCardAuthorizationInfo.creditCardBin = billingInfo.creditCardInfo.bin
        } else {
            creditCardAuthorizationInfo.billingInfoId = creditCard.billingInfo ? creditCard.billingInfo.id : null
            creditCardAuthorizationInfo.creditCardBin = creditCard.buildBin()
        }

        creditCardAuthorizationInfo.save(failOnError: true)

        return result
    }

    public Map capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        return cieloManagerService.capture(creditCardAuthorizationInfo, isFallback)
    }

    public Map refund(String transactionIdentifier) {
        CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionIdentifier]).get()

        if (!creditCardAuthorizationInfo) {
            AsaasLogger.error("CieloCreditCardService -> erro no estorno de transação ${transactionIdentifier}: creditCardAuthorizationInfo não encontrado")
            return [success: false]
        }

        return refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, creditCardAuthorizationInfo.transactionReference, creditCardAuthorizationInfo.amountInCents)
    }

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, String transactionReference, Long amountInCents) {
        Boolean refundResult = cieloManagerService.refund(customer, customerAccount, transactionIdentifier, transactionReference, amountInCents)

        return [success: refundResult]
    }

    public Map tokenize(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        if (CreditCardUtils.getCreditCardBrand(creditCard.number) in CreditCardBrand.supportsZeroDollarAuth()) return tokenizeWithZeroDollarAuth(creditCard, customerAccount, isFallback)

        return tokenizeWithDefaultAuth(creditCard, customerAccount, isFallback)
    }

    private Map tokenizeWithZeroDollarAuth(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        Map result = cieloManagerService.tokenizeWithZeroDollarAuth(creditCard, customerAccount, isFallback)

        if (!result.success) creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, null, result.message, result.returnCode)

        if (!result.success || !result.instantBuyKey) return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: result.creditCardTransactionLogIdList, acquirerReturnCode: result.returnCode]

        return [success: true, instantBuyKey: result.instantBuyKey, customerToken: customerAccount.id, creditCardTransactionLogIdList: result.creditCardTransactionLogIdList]
    }

    private Map tokenizeWithDefaultAuth(CreditCard creditCard, CustomerAccount customerAccount, Boolean isFallback) {
        final BigDecimal authorizationValue = 1
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(customerAccount, creditCard, authorizationValue)

        Map result = cieloManagerService.authorize(creditCardTransactionVO, isFallback)

        if (!result.success) {
            creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.CIELO, creditCard, null, result.message, result.returnCode)
            return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: result.creditCardTransactionLogIdList, acquirerReturnCode: result.returnCode]
        }

        cieloManagerService.refund(customerAccount.provider, customerAccount, result.transactionIdentifier, result.transactionReference, MoneyUtils.valueInCents(authorizationValue))

        if (!result.instantBuyKey) return [success: false, errorMessage: "Transação não autorizada.", creditCardTransactionLogIdList: result.creditCardTransactionLogIdList]

        return [success: true, instantBuyKey: result.instantBuyKey, customerToken: customerAccount.id, creditCardTransactionLogIdList: result.creditCardTransactionLogIdList]
    }
}
