package com.asaas.service.accountowner

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSegment
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerAccountOwnerService {

    def bankSlipFeeService
    def childAccountParameterBinderService
    def creditCardFeeConfigService
    def customerAccountManagerService
    def customerCommissionCacheService
    def customerDealInfoCreditCardDynamicMccFeeConfigReplicationService
    def customerFeeService
    def customerInteractionService
    def customerParameterService
    def customerSegmentService
    def feeAdminService
    def pixCreditFeeService
    def sageAccountService

    public Customer setAccountOwner(Long customerId, Long accountOwnerId, Boolean replicateAccountOwnerFee) {
        Customer customer = Customer.get(customerId)
        Customer accountOwner = Customer.get(accountOwnerId)

        BusinessValidation businessValidation = canSetAccountOwner(customer, accountOwner)

        if (!businessValidation.isValid()) {
            DomainUtils.addError(customer, businessValidation.asaasErrors[0].getMessage())
            return customer
        }

        customer.accountOwner = accountOwner
        customer.accountOwner.save(failOnError: true)
        customerInteractionService.save(customer, "Vinculado com a conta pai ${accountOwner.getProviderName()}")

        if (replicateAccountOwnerFee) {
            applyAccountOwnerFee(customer, accountOwner)
        }

        CustomerFee customerFee = CustomerFee.query([customerId: customer.id]).get()
        CustomerFee accountOwnerCustomerFee = CustomerFee.query([customerId: accountOwner.id]).get()
        if (customerFee && accountOwnerCustomerFee) {
            customerFee.alwaysChargeTransferFee = accountOwnerCustomerFee.alwaysChargeTransferFee
            customerFee.paymentMessagingNotificationFeeValue = accountOwnerCustomerFee.paymentMessagingNotificationFeeValue
            customerFee.paymentSmsNotificationFeeValue = accountOwnerCustomerFee.paymentSmsNotificationFeeValue
            customerFee.save(failOnError: true)
        }

        CustomerSegment customerSegment = customerSegmentService.getFromAccountOwner(customer)
        if (customerSegment && customerSegment != customer.segment) {
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, customerSegment, false)
        }

        AccountManager accountManager = customerAccountManagerService.find(customer, null)
        if (accountManager) customerAccountManagerService.save(accountManager, customer, AccountManagerChangeOrigin.SET_MANUALLY_ACCOUNT_OWNER, false)

        customerParameterService.save(customer, CustomerParameterName.CANNOT_USE_REFERRAL, true)
        customerParameterService.save(customer.accountOwner, CustomerParameterName.CANNOT_USE_REFERRAL, true)

        childAccountParameterBinderService.applyAllParameters(accountOwner, customer)
        customerDealInfoCreditCardDynamicMccFeeConfigReplicationService.replicateToChildAccountIfPossible(accountOwner, customer)
        sageAccountService.onCustomerAccountOwnerUpdated(customer.id)

        customerCommissionCacheService.evictGetCommissionedCustomerId(customer)

        return customer
    }

    public Customer unlinkAccountOwner(Long childAccountId) {
        Customer childAccount = Customer.get(childAccountId)

        BusinessValidation businessValidation = canUnlinkAccountOwner(childAccount)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(childAccount, businessValidation.asaasErrors[0].getMessage())
            return childAccount
        }

        Customer accountOwner = Customer.get(childAccount.accountOwner.id)

        childAccount.accountOwner = null
        childAccount.save(failOnError: true)

        Boolean hasChildAccountCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: childAccount.id]).get().asBoolean()
        if (!hasChildAccountCustomerDealInfo) feeAdminService.updateAllFeesToDefaultValues(childAccount, "solicitação para desvincular da conta pai")

        customerInteractionService.save(childAccount, "Desvinculado da conta pai ${accountOwner.getProviderName()}")

        customerParameterService.save(childAccount, CustomerParameterName.CANNOT_USE_REFERRAL, false)
        sageAccountService.onCustomerAccountOwnerUpdated(childAccount.id)

        customerCommissionCacheService.evictGetCommissionedCustomerId(childAccount)

        return childAccount
    }

    public BusinessValidation canReceiveAccountOwner(Customer childAccount) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!childAccount) {
            businessValidation.addError("customer.setAccountOwner.childAccountNotFound")
            return businessValidation
        }

        if (childAccount.accountOwner) {
            businessValidation.addError("customer.setAccountOwner.alreadyHasAccountOwner")
            return businessValidation
        }

        Boolean isAccountOwner = Customer.childAccounts(childAccount, [exists: true]).get().asBoolean()

        if (isAccountOwner) {
            businessValidation.addError("customer.setAccountOwner.isAccountOwner")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation canSetAccountOwner(Customer customer, Customer accountOwner) {
        BusinessValidation businessValidation = canReceiveAccountOwner(customer)

        if (!businessValidation.isValid()) return businessValidation

        if (!accountOwner) {
            businessValidation.addError("customer.setAccountOwner.accountOwnerNotFound")
            return businessValidation
        }

        if (customer.id == accountOwner.id) {
            businessValidation.addError("customer.setAccountOwner.accountsEquals")
            return businessValidation
        }

        if (accountOwner.accountOwner) {
            businessValidation.addError("customer.setAccountOwner.alreadyHasAccountOwner")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation canUnlinkAccountOwner(Customer childAccount) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!childAccount.accountOwner) {
            businessValidation.addError("customer.unlinkAccountOwner.accountOwnerNotFound")
            return businessValidation
        }

        Boolean isAccountOwner = Customer.childAccounts(childAccount, [exists: true]).get().asBoolean()

        if (isAccountOwner) {
            businessValidation.addError("customer.unlinkAccountOwner.isAccountOwner")
            return businessValidation
        }

        return businessValidation
    }

    private void applyAccountOwnerFee(Customer customer, Customer accountOwner) {
        Boolean hasCustomerDealInfo = CustomerDealInfo.query([exists: true, customerId: accountOwner.id]).get().asBoolean()
        if (hasCustomerDealInfo) return

        BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(accountOwner)

        if (bankSlipFee) bankSlipFeeService.setupChildAccount(customer, [defaultValue: bankSlipFee.defaultValue, discountValue: bankSlipFee.discountValue, discountExpiration: bankSlipFee.discountExpiration])

        PixCreditFee pixCreditFee = PixCreditFee.query([customer: accountOwner]).get()

        if (pixCreditFee) {
            Map pixCreditFeeConfig = [
                type: pixCreditFee.type,
                fixedFee: pixCreditFee.fixedFee,
                fixedFeeWithDiscount: pixCreditFee.fixedFeeWithDiscount,
                discountExpiration: pixCreditFee.discountExpiration,
                percentageFee: pixCreditFee.percentageFee,
                minimumFee: pixCreditFee.minimumFee,
                maximumFee: pixCreditFee.maximumFee
            ]

            pixCreditFeeService.setupChildAccount(customer, pixCreditFeeConfig)
        }

        customerFeeService.replicateAccountOwnerConfig(customer)
        creditCardFeeConfigService.replicateAccountOwnerConfig(customer)
    }
}
