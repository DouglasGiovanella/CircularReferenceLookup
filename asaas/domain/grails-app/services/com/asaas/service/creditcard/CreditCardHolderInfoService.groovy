package com.asaas.service.creditcard

import com.asaas.creditcard.HolderInfo
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.billinginfo.CreditCardHolderInfo

import grails.transaction.Transactional

@Transactional
class CreditCardHolderInfoService {
	
	public CreditCardHolderInfo save(HolderInfo holderInfo, BillingInfo billingInfo) {
		CreditCardHolderInfo creditCardHolderInfo = CreditCardHolderInfo.query([billingInfoId: billingInfo.id]).get()
		if (!creditCardHolderInfo) creditCardHolderInfo = new CreditCardHolderInfo()

		creditCardHolderInfo.properties = holderInfo.properties
		creditCardHolderInfo.billingInfoId = billingInfo.id

		creditCardHolderInfo.save(flush: true, failOnError: true)

		return creditCardHolderInfo
	}

}
