package com.asaas.service.fee

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.creditcard.CreditCardFeeConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.service.accountowner.PixCreditFeeParameterService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FeeAdminChildAccountReplicationService {

    def bankSlipFeeParameterService
    def creditCardFeeConfigParameterService
    def customerFeeParameterService
    def childAccountParameterBinderService
    def pixCreditFeeParameterService
    def asyncActionService

    public void setBankSlipFeeManuallyForChildAccounts(Long customerId, BigDecimal defaultValue, BigDecimal discountValue, Date discountExpiration) {
        Map parsedBankSlipFeeConfig = bankSlipFeeParameterService.parseBankSlipFeeConfigForSave(defaultValue.toString(), discountValue.toString(), CustomDateUtils.fromDate(discountExpiration as Date), null)
        bankSlipFeeParameterService.saveParameter(customerId, parsedBankSlipFeeConfig)

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(customerId, ["defaultValue", "discountValue", "discountExpirationDate"], BankSlipFee.simpleName)
    }

    public BankSlipFee validateSetBankSlipFeeManuallyForChildAccounts(Long accountOwnerId) {
        BankSlipFee validatedDomain = new BankSlipFee()

        Map search = [:]
        search.exists = true
        search.accountOwnerId = accountOwnerId
        search.type = BankSlipFee.simpleName
        search."name[in]" = ["defaultValue", "discountValue", "discountExpirationDate"]

        Boolean hasBankSlipFeeParameter = ChildAccountParameter.query(search).get().asBoolean()
        if (hasBankSlipFeeParameter) DomainUtils.addError(validatedDomain, Utils.getMessageProperty("feeAdmin.validationMessage.customerAlreadyHasParameter", ["taxas de boleto"]))

        return validatedDomain
    }

    public CreditCardFeeConfig validateSetCreditCardFeeManuallyForChildAccounts(Long accountOwnerId) {
        CreditCardFeeConfig validatedDomain = new CreditCardFeeConfig()

        Map search = [:]
        search.exists = true
        search.accountOwnerId = accountOwnerId
        search.type = CreditCardFeeConfig.simpleName
        search."name[in]" = CreditCardFeeConfig.ALLOWED_CREDIT_CARD_FEE_CONFIG_FIELD_LIST

        Boolean hasCreditCardFeeParameter = ChildAccountParameter.query(search).get().asBoolean()
        if (hasCreditCardFeeParameter) DomainUtils.addError(validatedDomain, Utils.getMessageProperty("feeAdmin.validationMessage.customerAlreadyHasParameter", ["taxas de cartão de crédito"]))

        return validatedDomain
    }

    public void setCreditCardFeeManuallyForChildAccounts(Long customerId, Map creditCardFeeConfig) {
        creditCardFeeConfigParameterService.saveParameter(customerId, creditCardFeeConfig)

        childAccountParameterBinderService.applyParameterListForAllChildAccounts(customerId, CreditCardFeeConfig.ALLOWED_CREDIT_CARD_FEE_CONFIG_FIELD_LIST, CreditCardFeeConfig.simpleName)
    }

    public PixCreditFee validateSetPixCreditFeeForChildAccounts(Long accountOwnerId) {
        PixCreditFee validatedPixCreditFee = new PixCreditFee()

        Map search = [
            exists: true,
            accountOwnerId: accountOwnerId,
            type: PixCreditFee.simpleName,
            "name[in]": PixCreditFeeParameterService.ALLOWED_FEE_CONFIG_FIELD_LIST
        ]

        Boolean hasPixCreditFeeParameter = ChildAccountParameter.query(search).get().asBoolean()
        if (hasPixCreditFeeParameter) DomainUtils.addError(validatedPixCreditFee, Utils.getMessageProperty("feeAdmin.validationMessage.customerAlreadyHasParameter", ["taxas de crédito de Pix"]))

        return validatedPixCreditFee
    }

    public void setPixCreditFeeForChildAccounts(Customer customer, PixCreditFee pixCreditFee) {
        Map parsedPixCreditFeeConfig = pixCreditFeeParameterService.parsePixCreditFeeConfigForSave(pixCreditFee.type.toString(), pixCreditFee.fixedFee?.toString(),
            pixCreditFee.fixedFeeWithDiscount?.toString(), CustomDateUtils.fromDate(pixCreditFee.discountExpiration as Date), null,
            pixCreditFee.percentageFee?.toString(), pixCreditFee.minimumFee?.toString(), pixCreditFee.maximumFee?.toString())

        pixCreditFeeParameterService.saveParameter(customer.id, parsedPixCreditFeeConfig)
        childAccountParameterBinderService.applyParameterListForAllChildAccounts(customer.id, PixCreditFeeParameterService.ALLOWED_FEE_CONFIG_FIELD_LIST, PixCreditFee.simpleName)
    }

    public CustomerFee validateSetCustomerFeeManuallyForChildAccounts(Long accountOwnerId, Map feeConfig) {
        CustomerFee validatedDomain = new CustomerFee()

        Map search = [:]
        search.exists = true
        search.accountOwnerId = accountOwnerId
        search.type = CustomerFee.simpleName
        search."name[in]" = feeConfig.collect { it.key }

        Boolean hasCustomerFeeParameter = ChildAccountParameter.query(search).get().asBoolean()

        if (hasCustomerFeeParameter) {
            String feeDescription = Utils.getMessageProperty("feeDescription.${feeConfig.collect { it.key }.first()}")
            DomainUtils.addError(validatedDomain, Utils.getMessageProperty("feeAdmin.validationMessage.customerAlreadyHasParameter", [feeDescription]))
        }

        return validatedDomain
    }

    public void setCustomerFeeManuallyForChildAccounts(Long customerId, Map feeConfig) {
        feeConfig.each {
            customerFeeParameterService.saveParameter(customerId, it.key, it.value)
            asyncActionService.saveChildAccountCustomerFeeReplication(customerId, it.key)
        }
    }
}
