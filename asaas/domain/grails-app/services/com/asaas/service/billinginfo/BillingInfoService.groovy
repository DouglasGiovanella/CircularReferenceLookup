package com.asaas.service.billinginfo

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.HolderInfo
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.billinginfo.CreditCardInfo
import com.asaas.domain.billinginfo.CreditCardInfoCde
import com.asaas.domain.billinginfo.GlobalCreditCardBillingInfo
import com.asaas.domain.customer.CustomerAccount
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

import java.security.SecureRandom

@Transactional
class BillingInfoService {

    def asaasMoneyService
	def creditCardInfoCdeService
	def creditCardInfoService
	def creditCardHolderInfoService

    def save(BillingType billingType, CustomerAccount customerAccount, CreditCard creditCard, HolderInfo holderInfo, String publicId) {
		BillingInfo billingInfo
		try {
			List<Long> creditCardInfoIdList = CreditCardInfoCde.query([column: "creditCardInfoId", expiryMonth: creditCard.expiryMonth, expiryYear: creditCard.expiryYear, cardHash: CreditCard.buildNumberHash(creditCard.number)]).list()
			if (creditCardInfoIdList) {
				billingInfo = BillingInfo.find("from BillingInfo b where b.creditCardInfo.lastDigits = :lastDigits and b.creditCardInfo.brand = :brand and b.customerAccount = :customerAccount and b.creditCardInfo.gateway = :gateway and b.creditCardInfo.id in (:creditCardInfoIdList)",
					[customerAccount: customerAccount, lastDigits: creditCard.lastDigits, brand: creditCard.brand, gateway: creditCard.gateway, creditCardInfoIdList: creditCardInfoIdList])
			}

			if (billingInfo) {
                copyToGlobalCreditCardInfoIfNecessary(billingInfo.publicId)
                return billingInfo
            }

			billingInfo = new BillingInfo()
			billingInfo.customerAccount = customerAccount
			billingInfo.billingType = billingType
			billingInfo.publicId = publicId
			billingInfo.save(flush: true, failOnError: true)

			billingInfo.creditCardInfo = creditCardInfoService.save(creditCard, billingInfo)
			if (holderInfo) creditCardHolderInfoService.save(holderInfo, billingInfo)

			billingInfo.save(flush: true, failOnError: true)

            copyToGlobalCreditCardInfoIfNecessary(billingInfo.publicId)
		} catch (Exception e) {
			AsaasLogger.error("Problema no Save do BillingInfo", e)
		}

		return billingInfo
	}

    public BillingInfo findByPublicId(Long providerId, String publicId) {
        return BillingInfo.find(providerId, publicId)
    }

    public BillingInfo findByPublicId(CustomerAccount customerAccount, String billingInfoPublicId) {
        BillingInfo billingInfo = BillingInfo.find(customerAccount, billingInfoPublicId)

        if (!billingInfo) {
            billingInfo = copyFromSameAccountOwner(customerAccount, billingInfoPublicId)
        }

        if (!billingInfo) {
            billingInfo = copyFromGlobalCreditCardInfoIfPossible(customerAccount, billingInfoPublicId)
        }

        if (!billingInfo) {
            billingInfo = copyFromChildAccount(customerAccount, billingInfoPublicId)
        }

        return billingInfo
    }

	public BillingInfo copyFromSameAccountOwner(CustomerAccount customerAccount, String publicId) {
		if (!customerAccount.provider.accountOwner) {
			return null
		}

		String hql = "from BillingInfo b where b.customerAccount.provider.accountOwner = :accountOwner and b.publicId = :publicId order by id"
		BillingInfo sourceBillingInfo = BillingInfo.executeQuery(hql, [accountOwner: customerAccount.provider.accountOwner, publicId: publicId])[0]

		if (!sourceBillingInfo) return null

		BillingInfo billingInfo = copyBillingInfo(customerAccount, sourceBillingInfo)

		return billingInfo
	}

	public BillingInfo copyFromGlobalCreditCardInfoIfPossible(CustomerAccount customerAccount, String publicId) {
		if (!asaasMoneyService.isAsaasMoneyRequest()) return null

		GlobalCreditCardBillingInfo globalInfo = GlobalCreditCardBillingInfo.query([publicId: publicId]).get()

		if (!globalInfo) return null

		BillingInfo billingInfo = new BillingInfo(customerAccount: customerAccount, publicId: globalInfo.publicId, billingType: BillingType.MUNDIPAGG_CIELO)
		billingInfo.save(flush: true, failOnError: true)

		CreditCardInfo creditCardInfo = new CreditCardInfo(billingInfo: billingInfo)
		creditCardInfo.properties = Utils.bindPropertiesFromDomainClass(globalInfo, ["id", "dateCreated", "lastUpdated", "creditCardHolderInfo"])
		creditCardInfo.save(flush: true, failOnError: true)

		creditCardInfoCdeService.saveCreditCardInfoCdeFromCreditCardInfo(creditCardInfo, globalInfo)

		return billingInfo
	}

    public void invalidateTokenIfNecessary(BillingInfo billingInfo) {
        if (!shouldInvalidateToken(billingInfo)) return

        creditCardInfoCdeService.invalidateToken(billingInfo.creditCardInfo)
    }

    private GlobalCreditCardBillingInfo copyToGlobalCreditCardInfoIfNecessary(String publicId) {
        if (!asaasMoneyService.isAsaasMoneyRequest()) return null

        GlobalCreditCardBillingInfo globalInfo = GlobalCreditCardBillingInfo.query([publicId: publicId]).get()
        if (globalInfo) return globalInfo

        BillingInfo billingInfo = BillingInfo.query([publicId: publicId]).get()

        globalInfo = new GlobalCreditCardBillingInfo()
        globalInfo.properties = Utils.bindPropertiesFromDomainClass(billingInfo.creditCardInfo, ["id", "dateCreated", "lastUpdated", "billingInfo"])
        globalInfo.publicId = billingInfo.publicId
        globalInfo.save(failOnError: true)

		creditCardInfoCdeService.saveGlobalCreditCardInfoCde(globalInfo, billingInfo.creditCardInfo)

        return globalInfo
	}

    private BillingInfo copyFromChildAccount(CustomerAccount customerAccount, String publicId) {
        String hql = "from BillingInfo b where b.customerAccount.provider.accountOwner = :accountOwner and b.publicId = :publicId order by id"
        BillingInfo sourceBillingInfo = BillingInfo.executeQuery(hql, [accountOwner: customerAccount.provider, publicId: publicId])[0]

        if (!sourceBillingInfo) return null

        BillingInfo billingInfo = copyBillingInfo(customerAccount, sourceBillingInfo)

        return billingInfo
    }

    private BillingInfo copyBillingInfo(CustomerAccount customerAccount, BillingInfo sourceBillingInfo) {
        BillingInfo newBillingInfo = new BillingInfo(customerAccount: customerAccount)
        newBillingInfo.properties = Utils.bindPropertiesFromDomainClass(sourceBillingInfo, ["customerAccount", "id", "dateCreated", "lastUpdated", "creditCardInfo", "creditCardHolderInfo"])
        newBillingInfo.save(flush: true, failOnError: true)

        CreditCardInfo creditCardInfo = new CreditCardInfo(billingInfo: newBillingInfo)
        creditCardInfo.properties = Utils.bindPropertiesFromDomainClass(sourceBillingInfo.creditCardInfo, ["billingInfo", "id", "dateCreated", "lastUpdated", "creditCardInfo", "creditCardHolderInfo", "isExpired"])
        creditCardInfo.save(flush: true, failOnError: true)

        creditCardInfoCdeService.saveCreditCardInfoCdeFromCreditCardInfo(creditCardInfo, sourceBillingInfo.creditCardInfo)

        return newBillingInfo
    }

    private Boolean shouldInvalidateToken(BillingInfo billingInfo) {
        if (!billingInfo.creditCardInfo.gateway.isCieloApplicable()) return false
        if (billingInfo.creditCardInfo.buildToken().startsWith(CreditCardInfoCde.TEXT_TO_CONCAT_ON_TOKEN_INVALIDATION)) return false
        if (new SecureRandom().nextInt(1000) > 6) return false

        return true
    }
}
