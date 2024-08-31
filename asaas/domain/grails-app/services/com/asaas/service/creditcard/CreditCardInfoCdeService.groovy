package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.domain.billinginfo.CreditCardInfo
import com.asaas.domain.billinginfo.CreditCardInfoCde
import com.asaas.domain.billinginfo.GlobalCreditCardBillingInfo
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.StringUtils

@Transactional
class CreditCardInfoCdeService {

    def kremlinCreditCardService
    def messageService

    public CreditCardInfoCde saveCreditCardInfoCdeFromCreditCard(CreditCardInfo creditCardInfo, CreditCard creditCard, String customerAccountCpfCnpj) {
		CreditCardInfoCde creditCardInfoCde = CreditCardInfoCde.query([creditCardInfoId: creditCardInfo.id]).get()
		if (!creditCardInfoCde) creditCardInfoCde = new CreditCardInfoCde()

		creditCardInfoCde.creditCardInfoId = creditCardInfo.id
        creditCardInfoCde.token = creditCard.token
        creditCardInfoCde.expiryMonth = StringUtils.leftPad(creditCard.expiryMonth.toInteger().toString(), 2, "0")
        creditCardInfoCde.expiryYear = StringUtils.leftPad(creditCard.expiryYear.toInteger().toString(), 4, "0")
        creditCardInfoCde.cardHash = CreditCard.buildNumberHash(creditCard.number)
		creditCardInfoCde.holderName = Utils.sanitizeString(creditCard.holderName)
		creditCardInfoCde.customerAccountCpfCnpj = customerAccountCpfCnpj

        creditCardInfoCde.save(flush: true, failOnError: true)

		return creditCardInfoCde
	}

	public CreditCardInfoCde saveGlobalCreditCardInfoCde(GlobalCreditCardBillingInfo globalCreditCardBillingInfo, CreditCardInfo creditCardInfo) {
		CreditCardInfoCde creditCardInfoCde = CreditCardInfoCde.query([globalCreditCardBillingInfoId: globalCreditCardBillingInfo.id]).get()
		if (!creditCardInfoCde) creditCardInfoCde = new CreditCardInfoCde()

		creditCardInfoCde.globalCreditCardBillingInfoId = globalCreditCardBillingInfo.id
        creditCardInfoCde.token = creditCardInfo.buildToken()
        creditCardInfoCde.expiryMonth = creditCardInfo.buildExpiryMonth()
        creditCardInfoCde.expiryYear = creditCardInfo.buildExpiryYear()
        creditCardInfoCde.cardHash = creditCardInfo.buildCardHash()
		creditCardInfoCde.holderName = Utils.sanitizeString(creditCardInfo.buildHolderName())
		creditCardInfoCde.customerAccountCpfCnpj = creditCardInfo.getCreditCardInfoCde()?.customerAccountCpfCnpj

        creditCardInfoCde.save(flush: true, failOnError: true)

		return creditCardInfoCde
	}

	public CreditCardInfoCde saveCreditCardInfoCdeFromCreditCardInfo(CreditCardInfo newCreditCardInfo, oldCreditCardInfo) {
		CreditCardInfoCde creditCardInfoCde = CreditCardInfoCde.query([creditCardInfoId: newCreditCardInfo.id]).get()
		if (!creditCardInfoCde) creditCardInfoCde = new CreditCardInfoCde()

		creditCardInfoCde.creditCardInfoId = newCreditCardInfo.id
        creditCardInfoCde.token = oldCreditCardInfo.buildToken()
        creditCardInfoCde.expiryMonth = oldCreditCardInfo.buildExpiryMonth()
        creditCardInfoCde.expiryYear = oldCreditCardInfo.buildExpiryYear()
        creditCardInfoCde.cardHash = oldCreditCardInfo.buildCardHash()
		creditCardInfoCde.holderName = Utils.sanitizeString(oldCreditCardInfo.buildHolderName())
		creditCardInfoCde.customerAccountCpfCnpj = oldCreditCardInfo.getCreditCardInfoCde()?.customerAccountCpfCnpj

        creditCardInfoCde.save(flush: true, failOnError: true)

		return creditCardInfoCde
	}

    public void invalidateToken(CreditCardInfo creditCardInfo) {
        CreditCardInfoCde creditCardInfoCde = creditCardInfo.getCreditCardInfoCde()
        creditCardInfoCde.token = CreditCardInfoCde.TEXT_TO_CONCAT_ON_TOKEN_INVALIDATION + creditCardInfoCde.token
        creditCardInfoCde.save(failOnError: true)
    }

    public Boolean removeExpiredSensitiveData() {
        List<Long> creditCardInfoCdeIdList = CreditCardInfoCde.query([column: "id", "expiryDate[lt]": CustomDateUtils.truncate(new Date(), Calendar.MONTH), includeDeleted: true]).list()

        if (!creditCardInfoCdeIdList) return false

        Integer removedDataCount = 0

        final Integer batchSize = 25
        final Integer flushEvery = 25
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(creditCardInfoCdeIdList, batchSize, flushEvery, { Long creditCardInfoCdeId ->
            CreditCardInfoCde creditCardInfoCde = CreditCardInfoCde.get(creditCardInfoCdeId)
            if (!creditCardInfoCde.isExpired()) throw new RuntimeException("Cartão não está expirado e os dados não foram removidos [creditCardInfoCdeId: ${creditCardInfoCdeId}].")

            creditCardInfoCde.expiryMonth = null
            creditCardInfoCde.expiryYear = null
            creditCardInfoCde.holderName = null
            creditCardInfoCde.save(failOnError: true)

            CreditCardInfo creditCardInfo = CreditCardInfo.read(creditCardInfoCde.creditCardInfoId)
            if (creditCardInfo?.gateway?.isKremlin()) kremlinCreditCardService.deleteTokenizedCreditCard(creditCardInfoCde.token, creditCardInfo.billingInfo.publicId)

            removedDataCount++
        }, [logErrorMessage: "CreditCardInfoCdeService.removeExpiredSensitiveData >> Ocorreu um erro ao remover dados do cartão.",
            appendBatchToLogErrorMessage: true]
        )

        Integer errorCount = creditCardInfoCdeIdList.size() - removedDataCount
        messageService.sendSensitiveCreditCardDataRemovalReport(removedDataCount, errorCount)

        return true
    }
}
