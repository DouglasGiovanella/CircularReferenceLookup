package com.asaas.service.rede

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.creditcard.CreditCardTransactionVO
import com.asaas.creditcard.CreditCardTransactionDeviceInfoVO
import com.asaas.creditcard.CreditCardUtils
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.CreditCardAuthorizationInfo
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletWebRequest

@Transactional
class RedeCreditCardService {

    def creditCardBlackListService
    def redeManagerService

    public Map capture(Installment installment, Payment payment, CreditCard creditCard, Boolean isFallback) {
        Map result = redeManagerService.capture(installment, payment, creditCard, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.REDE, creditCard, null, result.message, result.returnCode)
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

        if (creditCard.billingInfo) {
            creditCardAuthorizationInfo.billingInfoId = creditCard.billingInfo.id
            creditCardAuthorizationInfo.creditCardBin = creditCard.billingInfo.creditCardInfo.bin
        } else {
            creditCardAuthorizationInfo.creditCardBin = creditCard.buildBin()
        }

        creditCardAuthorizationInfo.save(failOnError: true)

        return result
    }

    public Map authorize(Installment installment, Payment payment, CreditCard creditCard, CreditCardTransactionDeviceInfoVO deviceInfo, Boolean withThreeDSecure, Boolean isFallback) {
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(installment, payment, creditCard, null)
        creditCardTransactionVO.deviceInfo = deviceInfo
        creditCardTransactionVO.withThreeDSecure = withThreeDSecure

        if (creditCardTransactionVO.withThreeDSecure) {
            ServletWebRequest request = RequestContextHolder.getRequestAttributes()
            creditCardTransactionVO.userAgent = request.getHeader("User-Agent")
        }

        Map result = redeManagerService.authorize(creditCardTransactionVO, isFallback)

        if (!result.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.REDE, creditCard, null, result.message, result.returnCode)
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
        creditCardAuthorizationInfo.withThreeDSecure = withThreeDSecure

        if (creditCard.billingInfo) {
            creditCardAuthorizationInfo.billingInfoId = creditCard.billingInfo.id
            creditCardAuthorizationInfo.creditCardBin = creditCard.billingInfo.creditCardInfo.bin
        } else {
            creditCardAuthorizationInfo.creditCardBin = creditCard.buildBin()
        }

        result.creditCardAuthorizationInfo = creditCardAuthorizationInfo.save(failOnError: true)

        return result
    }

    public Map capture(CreditCardAuthorizationInfo creditCardAuthorizationInfo, Boolean isFallback) {
        return redeManagerService.capture(creditCardAuthorizationInfo, isFallback)
    }

    public Map refund(String transactionIdentifier) {
        CreditCardAuthorizationInfo creditCardAuthorizationInfo = CreditCardAuthorizationInfo.query([transactionIdentifier: transactionIdentifier]).get()

        if (!creditCardAuthorizationInfo) {
            AsaasLogger.error("RedeCreditCardService.refund >>> Erro ao estornar a transação: ${transactionIdentifier}: creditCardAuthorizationInfo não encontrado")
            return [success: false]
        }

        return refund(creditCardAuthorizationInfo.payment.provider, creditCardAuthorizationInfo.payment.customerAccount, creditCardAuthorizationInfo.transactionIdentifier, creditCardAuthorizationInfo.amountInCents)
    }

    public Map refund(Customer customer, CustomerAccount customerAccount, String transactionIdentifier, Long amountInCents) {
        Boolean refundSuccess = redeManagerService.refund(customer, customerAccount, transactionIdentifier, amountInCents)

        return [success: refundSuccess]
    }

    public Map validateCreditCardInfo(CustomerAccount customerAccount, CreditCard creditCard, Boolean isFallback) {
        if (CreditCardUtils.getCreditCardBrand(creditCard.number) in CreditCardBrand.supportsZeroDollarAuth()) return validateCreditCardInfoWithZeroDollarAuth(customerAccount, creditCard, isFallback)

        return validateCreditCardInfoWithDefaultAuth(customerAccount, creditCard, isFallback)
    }

    private validateCreditCardInfoWithZeroDollarAuth(CustomerAccount customerAccount, CreditCard creditCard, Boolean isFallback) {
        Map zeroDollarResponseMap = redeManagerService.zeroDollarAuth(customerAccount, creditCard, isFallback)

        if (!zeroDollarResponseMap.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.REDE, creditCard, null, zeroDollarResponseMap.message, zeroDollarResponseMap.returnCode)
            zeroDollarResponseMap.blockedOnBlackList = blockedOnBlackList
        }

        return zeroDollarResponseMap
    }

    private validateCreditCardInfoWithDefaultAuth(CustomerAccount customerAccount, CreditCard creditCard, Boolean isFallback) {
        final BigDecimal authorizationValue = 1
        CreditCardTransactionVO creditCardTransactionVO = new CreditCardTransactionVO(customerAccount, creditCard, authorizationValue)

        Map authorizationResponseMap = redeManagerService.authorize(creditCardTransactionVO, isFallback)

        if (!authorizationResponseMap.success) {
            Boolean blockedOnBlackList = creditCardBlackListService.saveIfNecessary(CreditCardAcquirer.REDE, creditCard, null, authorizationResponseMap.message, authorizationResponseMap.returnCode)
            authorizationResponseMap.blockedOnBlackList = blockedOnBlackList

            return authorizationResponseMap
        }

        Boolean refundSuccess = redeManagerService.refund(customerAccount.provider, customerAccount, authorizationResponseMap.transactionIdentifier, authorizationResponseMap.amountInCents.longValue())

        if (!refundSuccess) AsaasLogger.error("RedeCreditCardService.validateCreditCardInfoWithDefaultAuth >>> Erro ao estornar a transação [TID: ${authorizationResponseMap.transactionIdentifier}].")

        return authorizationResponseMap
    }
}
