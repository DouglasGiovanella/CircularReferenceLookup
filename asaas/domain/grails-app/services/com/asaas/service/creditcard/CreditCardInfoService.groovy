package com.asaas.service.creditcard

import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardGateway
import com.asaas.creditcard.CreditCardUtils
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.billinginfo.CreditCardInfo

import grails.transaction.Transactional

@Transactional
class CreditCardInfoService {

	def creditCardInfoCdeService
	def crypterService

	public CreditCardInfo save(CreditCard creditCard, BillingInfo billingInfo) {
		CreditCardInfo creditCardInfo = CreditCardInfo.findOrCreateWhere(billingInfo: billingInfo)

		creditCardInfo.lastDigits 	= creditCard.lastDigits
		creditCardInfo.brand 		= creditCard.brand
		creditCardInfo.gateway		= creditCard.tokenizationGateway ?: creditCard.gateway
        creditCardInfo.bin          = creditCard.buildBin()

        if (creditCard.customerToken) creditCardInfo.customerToken = creditCard.customerToken

		creditCardInfo.save(flush:true, failOnError:true)

		creditCardInfoCdeService.saveCreditCardInfoCdeFromCreditCard(creditCardInfo, creditCard, billingInfo.customerAccount.cpfCnpj)

		return creditCardInfo
	}
}
