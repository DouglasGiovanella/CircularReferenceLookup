package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.creditcard.CreditCardBlackList
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import static grails.async.Promises.task
import grails.transaction.Transactional

@Transactional
class CreditCardBlackListService {

    public Boolean saveIfNecessary(CreditCardAcquirer creditCardAcquirer, CreditCard creditCard, BillingInfo billingInfo, String acquirerMessage, String acquirerReturnCode) {
        if (!creditCard && !billingInfo) return false

        if (!acquirerMessage) {
            if (!acquirerReturnCode) return false

            acquirerMessage = ""
        }

        String creditCardHash
        CreditCardBrand creditCardBrand
        String creditCardLastDigits

        if (billingInfo) {
            creditCardHash = CreditCard.buildTokenizedCreditCardHash(billingInfo.creditCardInfo.buildToken())
            creditCardBrand = billingInfo.creditCardInfo.brand
            creditCardLastDigits = billingInfo.creditCardInfo.lastDigits
        } else {
            creditCardHash = CreditCard.buildCreditCardFullInfoHash(creditCard)
            creditCardBrand = creditCard.brand
            creditCardLastDigits = creditCard.lastDigits
        }

        if (!acquirerMessageIsInBlackList(creditCardAcquirer, CreditCardBrand.convert(creditCardBrand), acquirerMessage, acquirerReturnCode)) return false

        if (acquirerReturnCode) {
            acquirerMessage += " (Acquirer Return Code: ${acquirerReturnCode})"
        }

        saveAsync(creditCardHash, creditCardBrand, creditCardLastDigits, acquirerMessage)

        return true
    }

    public void delete(Long creditCardBlackListId, String lastDigits) {
        CreditCardBlackList creditCardBlackList = CreditCardBlackList.query([id: creditCardBlackListId, lastDigits: lastDigits]).get()

        if (!creditCardBlackList) throw new BusinessException("Cartão de crédito com ID [${creditCardBlackListId}] e com os ultimos digitos [${lastDigits}] não foi encontrado na Black List.")

        AsaasLogger.info("CreditCardBlackListService.delete >> Excluindo o cartão com ID [${creditCardBlackListId}] e com os ultimos digitos [${lastDigits}] da Black List.")

        creditCardBlackList.deleted = true
        creditCardBlackList.save(failOnError: true)
    }

    private Boolean acquirerMessageIsInBlackList(CreditCardAcquirer creditCardAcquirer, CreditCardBrand creditCardBrand, String acquirerMessage, String acquirerReturnCode) {
        if (CreditCardBlackList.ACQUIRER_MESSAGE_BLACK_LIST.any { it.toLowerCase() == acquirerMessage.toLowerCase() }) return true

        if (acquirerReturnCode) {
            if (!creditCardBrand) return false

            List<String> selectedBrandReturnCodeBlackList

            if (creditCardBrand.isElo() || creditCardBrand.isDiners())  {
                selectedBrandReturnCodeBlackList = CreditCardBlackList.ELO_BRAND_RETURN_CODE_BLACK_LIST
            } else if (creditCardBrand.isVisa()) {
                selectedBrandReturnCodeBlackList = CreditCardBlackList.VISA_BRAND_RETURN_CODE_BLACK_LIST
            } else if (creditCardBrand.isMastercard() || creditCardBrand.isHipercard()) {
                selectedBrandReturnCodeBlackList = CreditCardBlackList.MASTER_BRAND_RETURN_CODE_BLACK_LIST
            } else if (creditCardBrand.isAmex()) {
                selectedBrandReturnCodeBlackList = CreditCardBlackList.AMEX_BRAND_RETURN_CODE_BLACK_LIST
            }

            if (!selectedBrandReturnCodeBlackList) return false

            if (selectedBrandReturnCodeBlackList.contains(acquirerReturnCode)) return true
        }

        return false
    }

    private void saveAsync(String creditCardHash, CreditCardBrand brand, String lastDigits, String reason) {
        task {
            Utils.withNewTransactionAndRollbackOnError ( {
                CreditCardBlackList creditCardBlackList = new CreditCardBlackList()
                creditCardBlackList.creditCardFullInfoHash = creditCardHash
                creditCardBlackList.reason = reason
                creditCardBlackList.brand = brand
                creditCardBlackList.lastDigits = lastDigits

                creditCardBlackList.save(flush: true, failOnError: true)
            }, [logErrorMessage: "CreditCardBlackListService.saveAsync >>> Erro ao incluir o cartão na black list"] )
        }
    }
}
