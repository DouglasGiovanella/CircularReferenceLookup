package com.asaas.service.creditcard

import com.asaas.domain.creditcard.CreditCardFeeNegotiatedForBrandConfig
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class CreditCardFeeNegotiatedForBrandConfigService {

    public CreditCardFeeNegotiatedForBrandConfig save(Customer customer, CreditCardFeeNegotiatedForBrandConfig creditCardFeeNegotiatedForBrandConfig) {
        CreditCardFeeNegotiatedForBrandConfig newCreditCardFeeNegotiatedForBrandConfig = new CreditCardFeeNegotiatedForBrandConfig()
        newCreditCardFeeNegotiatedForBrandConfig.customer = customer
        newCreditCardFeeNegotiatedForBrandConfig.brand = creditCardFeeNegotiatedForBrandConfig.brand
        newCreditCardFeeNegotiatedForBrandConfig.fixedFee = creditCardFeeNegotiatedForBrandConfig.fixedFee
        newCreditCardFeeNegotiatedForBrandConfig.percentageFee = creditCardFeeNegotiatedForBrandConfig.percentageFee
        newCreditCardFeeNegotiatedForBrandConfig.installmentCount = creditCardFeeNegotiatedForBrandConfig.installmentCount
        newCreditCardFeeNegotiatedForBrandConfig.save(failOnError: true)

        return newCreditCardFeeNegotiatedForBrandConfig
    }

	public void saveFromAccountOwner(Long accountOwnerId, Long childAccountId) {
		List<CreditCardFeeNegotiatedForBrandConfig> accountOwnerCreditCardFeeNegotiatedForBrandConfig = CreditCardFeeNegotiatedForBrandConfig.query([customerId: accountOwnerId]).list(readOnly: true)
        if (!accountOwnerCreditCardFeeNegotiatedForBrandConfig) return

        Customer childAccount = Customer.read(childAccountId)
        for (CreditCardFeeNegotiatedForBrandConfig creditCardFeeNegotiatedForBrandConfig : accountOwnerCreditCardFeeNegotiatedForBrandConfig) {
            save(childAccount, creditCardFeeNegotiatedForBrandConfig)
        }
    }

}
