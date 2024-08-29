package com.asaas.service.customer

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.customer.CustomerSegment
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.exception.ResourceNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerAccountManagerService {

    def asaasSegmentioService
    def customerAdminService
    def customerInteractionService
    def messageService
    def asyncActionService

    public AccountManager save(AccountManager accountManager, Customer customer, AccountManagerChangeOrigin accountManagerOriginChange, Boolean isBatchMigration) {
        try {
            String oldAccountManagerName = customer.accountManager.name

            AccountManager validatedAccountManager = validateSave(accountManager, customer)
            if (validatedAccountManager.hasErrors()) return validatedAccountManager

            customer.accountManager = accountManager
            customer.save(failOnError: true)

            String description = Utils.getMessageProperty("customerInteraction.accountManagerChanged", [oldAccountManagerName, accountManager.name, accountManagerOriginChange.getLabel()])
            customerInteractionService.save(customer, description)

            Boolean mustSendEmailNotification = (accountManager.user.id != UserUtils.getCurrentUser()?.id && !isBatchMigration)
            if (mustSendEmailNotification) messageService.sendNewCustomerInManagerPortfolio(accountManager, customer)
            asaasSegmentioService.identify(customer.id, ["accountManagerEmail": customer.accountManager.email])

            saveInheritanceForChildAccountsIfPossible(customer, accountManager, accountManagerOriginChange)

            return accountManager
        } catch (Exception exception) {
            AsaasLogger.error("CustomerAccountManagerService.save >> Erro na mudança de gerente do cliente [${customer.id}]", exception)
            return null
        }
    }

    public AccountManager find(Customer customer, Long accountManagerId) {
        AccountManager accountManager = getFromAccountOwnerIfAvailable(customer)
        if (accountManager) return accountManager

        accountManager = getFromBusinessGroupIfAvailable(customer)
        if (accountManager) return accountManager

        if (accountManagerId) {
            AccountManager newAccountManager = AccountManager.get(accountManagerId)
            if (newAccountManager.isAvailableToCustomerSegment(customer.segment ?: Customer.INITIAL_SEGMENT)) return newAccountManager
        }

        if (customer.accountManager?.isAvailableToCustomerSegment(customer.segment ?: Customer.INITIAL_SEGMENT)) return customer.accountManager

        return getFromCustomerSegment(customer.segment)
    }

    public List<AccountManager> list(Customer customer) {
        if (!customer.segment) return [customer.accountManager]

        AccountManager accountManager = getFromAccountOwnerIfAvailable(customer)
        if (accountManager) return [accountManager]

        List<AccountManager> accountManagerList = listAvailableToManualAssign(customer)

        return accountManagerList
    }

    public AccountManager getFromCustomerSegment(CustomerSegment customerSegment) {
        List<Long> accountManagerIdList = AccountManager.availableToCustomerSegment([column: "id", customerSegment: customerSegment ?: Customer.INITIAL_SEGMENT]).list()
        if (!accountManagerIdList) throw new ResourceNotFoundException(Utils.getMessageProperty("accountManager.list.exception.resourceNotFound"))

        Collections.shuffle(accountManagerIdList)
        return AccountManager.get(accountManagerIdList[0])
    }

    private void saveInheritanceForChildAccountsIfPossible(Customer customer, AccountManager accountManager, AccountManagerChangeOrigin accountManagerOriginChange) {
        if (!accountManagerOriginChange.shouldInheritAccountManager()) return

        Boolean hasActiveAccountOwnerManagerAndSegmentInheritance = customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer)
        if (!hasActiveAccountOwnerManagerAndSegmentInheritance) return

        Boolean hasChildAccountToMigrate = Customer.childAccounts(customer, [exists: true, "accountManager[ne]": accountManager]).get().asBoolean()
        if (!hasChildAccountToMigrate) return

        asyncActionService.saveAccountOwnerManagerInheritance([accountOwnerId: customer.id, accountManagerId: accountManager.id])
    }

    private List<AccountManager> listAvailableToManualAssign(Customer customer) {
        List<AccountManager> accountManagerList
        Map search = ["customerSegment": customer.segment, order: 'asc', sort: 'name']

        if (customer.segment.isCorporate()) {
            accountManagerList = AccountManager.query(search).list()
        } else {
            accountManagerList = AccountManager.availableToCustomerSegment(search).list()
            if (!customer.accountManager.isAvailableToCustomerSegment(customer.segment)) accountManagerList.add(customer.accountManager)
        }

        return accountManagerList
    }

    private AccountManager getFromAccountOwnerIfAvailable(Customer customer) {
        if (!customer.accountOwner) return null

        if (customer.segment != customer.accountOwner.segment) return null

        if (customer.accountOwner.status.isInactive()) return null

        if (!customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer.accountOwner)) return null

        return customer.accountOwner.accountManager
    }

    private AccountManager getFromBusinessGroupIfAvailable(Customer customer) {
        BusinessGroup businessGroup = CustomerBusinessGroup.query([column: "businessGroup", customerId: customer.id]).get()
        if (!businessGroup) return null

        if (customer.segment != businessGroup.segment) return null

        return businessGroup.accountManager
    }

    private AccountManager validateSave(AccountManager accountManager, Customer customer) {
        AccountManager validatedDomain = new AccountManager()

        if (accountManager.id == customer.accountManagerId) {
            DomainUtils.addError(validatedDomain, "O gerente de conta deve ser diferente do atual.")
            return validatedDomain
        }

        BusinessGroup businessGroup = CustomerBusinessGroup.query([column: "businessGroup", customerId: customer.id]).get()
        if (businessGroup) {
            validatedDomain = validateSaveForCustomerLinkedToBusinessGroup(customer, businessGroup, accountManager)
        } else {
            validatedDomain = validateSaveForCustomerNotLinkedToCustomerBusinessGroup(accountManager)
        }

        if (customer.accountOwner) {
            validatedDomain = DomainUtils.copyAllErrorsFromObject(validatedDomain, validateSaveForChildAccount(accountManager, customer))
            return validatedDomain
        }

        CustomerSegment customerSegment = customer.segment ?: Customer.INITIAL_SEGMENT
        if (customerSegment != accountManager.customerSegment) {
            DomainUtils.addError(validatedDomain, "O gerente de conta não atende o segmento do cliente.")
        }

        return validatedDomain
    }

    private AccountManager validateSaveForCustomerLinkedToBusinessGroup(Customer customer, BusinessGroup businessGroup, AccountManager accountManager) {
        AccountManager validatedDomain = new AccountManager()

        Boolean hasActiveAccountOwnerManagerAndSegmentInheritance = customer.accountOwner && customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer.accountOwner)

        if (!hasActiveAccountOwnerManagerAndSegmentInheritance && businessGroup && accountManager != businessGroup.accountManager) {
            DomainUtils.addError(validatedDomain, "O gerente de conta deve ser o mesmo do grupo empresarial")
        }

        return validatedDomain
    }

    private AccountManager validateSaveForChildAccount(AccountManager accountManager, Customer customer) {
        AccountManager validatedDomain = new AccountManager()

        Boolean hasActiveAccountOwnerManagerAndSegmentInheritance = customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(customer.accountOwner)
        if (!hasActiveAccountOwnerManagerAndSegmentInheritance && customer.segment != accountManager.customerSegment) {
            DomainUtils.addError(validatedDomain, "O gerente de conta não atende o segmento do cliente.")
            return validatedDomain
        }

        if (!hasActiveAccountOwnerManagerAndSegmentInheritance) return validatedDomain

        if (!customer.accountOwner.status.isInactive() && accountManager != customer.accountOwner.accountManager) {
            DomainUtils.addError(validatedDomain, "O gerente de conta deve ser o mesmo da conta pai.")
            return validatedDomain
        }

        return validatedDomain
    }

    private AccountManager validateSaveForCustomerNotLinkedToCustomerBusinessGroup(AccountManager accountManager) {
        AccountManager validatedDomain = new AccountManager()

        if (!accountManager.customerPortfolio && !accountManager.customerSegment.isCorporate()) {
            DomainUtils.addError(validatedDomain, "O gerente de conta não tem sua carteira de clientes habilitada.")
        }

        return validatedDomain
    }
}
