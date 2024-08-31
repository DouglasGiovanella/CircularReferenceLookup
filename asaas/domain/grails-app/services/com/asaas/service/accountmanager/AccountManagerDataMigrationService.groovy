package com.asaas.service.accountmanager

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerStatus
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class AccountManagerDataMigrationService {

    def asyncActionService
    def businessGroupService
    def customerAccountManagerService
    def customerSegmentService

    private final Integer MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION = 2000

    public void executeDistributeCustomersPortfolioInSegmentAsyncAction() {
        final Integer maxItemsPerCycle = 1

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.DISTRIBUTE_CUSTOMERS_PORTFOLIO_IN_SEGMENT, maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountManagerId = Utils.toLong(asyncActionData.accountManagerId)

                distributeCustomersPortfolioInSegment(accountManagerId)

                if (!hasCustomerToMigrate(accountManagerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AccountManagerDataMigrationService.executeDistributeCustomersPortfolioInSegmentAsyncAction >> Erro na distribuição de carteira. AsyncActionID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void executeDistributeCustomerPortfolioInAccountManagerListAsyncAction() {
        final Integer maxItemsPerCycle = 1

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.DISTRIBUTE_CUSTOMERS_PORTFOLIO_IN_ACCOUNT_MANAGER_LIST, maxItemsPerCycle)) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountManagerId = Utils.toLong(asyncActionData.accountManagerId)

                distributeCustomerPortfolioInAccountManagerList(accountManagerId, asyncActionData.accountManagerIdList)

                if (!hasCustomerToMigrate(accountManagerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AccountManagerDataMigrationService.executeDistributeCustomerPortfolioInAccountManagerListAsyncAction >> Erro na distribuição de carteira. AsyncActionID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public Boolean hasCustomerToMigrate(Long accountManagerId) {
        if (listValidCustomerIdsToMigrate(accountManagerId, [:], 1)) return true

        return false
    }

    private void distributeCustomersPortfolioInSegment(Long accountManagerId) {
        List<Long> customerIdList = listCustomerIdsToMigrate(accountManagerId)
        if (!customerIdList) return

        migrateCustomerPortfolio(customerIdList, accountManagerId, null)
    }

    private void distributeCustomerPortfolioInAccountManagerList(Long oldAccountManagerId, List<Long> newAccountManagerIdList) {
        List<Long> customerIdList = listCustomerIdsToMigrate(oldAccountManagerId)
        if (!customerIdList) return

        Integer collateSize = Math.max(Math.round(customerIdList.size() / newAccountManagerIdList.size()), 1)
        List<List<Long>> customerToMigrateList = customerIdList.collate(collateSize)

        Integer index = 0
        for (Long newAccountManagerId : newAccountManagerIdList) {
            migrateCustomerPortfolio(customerToMigrateList[index], oldAccountManagerId, newAccountManagerId)
            index++
        }
    }

    private void migrateCustomerPortfolio(List<Long> customerIdList, Long oldAccountManagerId, Long newAccountManagerId) {
        migrateBusinessGroupAccountManagerIfNecessary(oldAccountManagerId, newAccountManagerId)

        Utils.forEachWithFlushSession(customerIdList, 25, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                if (!customer.segment) {
                    customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, Customer.INITIAL_SEGMENT, true)
                    return
                }

                AccountManager newAccountManager = customerAccountManagerService.find(customer, newAccountManagerId)
                newAccountManager = customerAccountManagerService.save(newAccountManager, customer, AccountManagerChangeOrigin.DISTRIBUTE_CUSTOMERS_PORTFOLIO, true)
                if (newAccountManager.hasErrors()) throw new ValidationException("Erro ao salvar novo gerente do cliente [${customer.id}]", newAccountManager.errors)
            }, [logErrorMessage: "AccountManagerDataMigrationService.migrateCustomerPortfolio >> Erro ao efetuar migração dos clientes do gerente de contas [${oldAccountManagerId}].",
                onError: { Exception exception -> throw exception }])
        })
    }

    private void migrateBusinessGroupAccountManagerIfNecessary(Long oldAccountManagerId, Long newAccountManagerId) {
        List<BusinessGroup> businessGroupList = BusinessGroup.query([accountManagerId: oldAccountManagerId]).list()

        if (!businessGroupList) return

        Utils.forEachWithFlushSession(businessGroupList, 5, { BusinessGroup businessGroup ->
            Utils.withNewTransactionAndRollbackOnError({
                AccountManager newAccountManager = newAccountManagerId ?  AccountManager.get(newAccountManagerId) : customerAccountManagerService.getFromCustomerSegment(businessGroup.segment)

                businessGroupService.update(businessGroup.id, businessGroup.name, businessGroup.segment, newAccountManager.id)
            }, [logErrorMessage: "AccountManagerDataMigrationService.migrateBusinessGroupAccountManager >> Erro ao definir novo gerente para o grupo empresarial [${businessGroup.id}]",
                onError: { Exception exception -> throw exception }])
        })
    }

    private List<Long> listCustomerIdsToMigrate(Long accountManagerId) {
        List<Long> customerIdList = listValidCustomerIdsToMigrate(accountManagerId, ["accountOwner[isNull]": true], MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION)

        if (!customerIdList) customerIdList = listValidCustomerIdsToMigrate(accountManagerId, ["accountOwner[isNotNull]": true], MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION)

        return customerIdList
    }

    private List<Long> listValidCustomerIdsToMigrate(Long accountManagerId, Map search, Integer max) {
        return Customer.createCriteria().list(max: max) {
            projections {
                property("id", "id")
            }

            eq("deleted", false)
            eq("accountManager.id", accountManagerId)
            "in"("status", CustomerStatus.values() - CustomerStatus.inactive())

            if (Boolean.valueOf(search."accountOwner[isNull]")) {
                isNull("accountOwner")
            }

            if (Boolean.valueOf(search."accountOwner[isNotNull]")) {
                isNotNull("accountOwner")
            }

            notExists CustomerBusinessGroup.where {
                setAlias("customerBusinessGroup")

                eq("deleted", false)
                eqProperty("customerBusinessGroup.customer.id", "this.id")
            }.id()
        }
    }
}
