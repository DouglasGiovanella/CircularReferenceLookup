package com.asaas.service.customerexternalauthorization

import com.asaas.customer.CustomerParameterName
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigSaveAdapter
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigUpdateAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.utils.Utils

import grails.async.Promise
import grails.transaction.Transactional
import grails.validation.ValidationException

import static grails.async.Promises.task
import static grails.async.Promises.waitAll

@Transactional
class CustomerExternalAuthorizationRequestConfigReplicationService {

    private static final int MAX_ITEMS_PER_ASYNC_ACTION = 80

    def asyncActionService
    def customerExternalAuthorizationRequestConfigService

    public void process() {
        for (Map asyncActionData : asyncActionService.listPendingChildAccountExternalAuthorizationRequestConfigReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerExternalAuthorizationRequestConfigType configType = CustomerExternalAuthorizationRequestConfigType.valueOf(asyncActionData.type)
                List<Long> childAccountIdList = listCustomersWithoutConfigOrOutdatedConfig(asyncActionData.accountOwnerId, [configType: configType, "customerId[in]": asyncActionData.childAccountIdList])

                Boolean hasError = false
                Utils.forEachWithFlushSession(childAccountIdList, 50, { Long childAccountId ->
                    Utils.withNewTransactionAndRollbackOnError({
                        replicateForChildAccount(asyncActionData.accountOwnerId, childAccountId, configType)
                    }, [logErrorMessage: "CustomerExternalAuthorizationRequestConfigReplicationService.start >> Erro ao replicar informação para conta filha [CustomerId: ${childAccountId}] [AsyncActionID: ${asyncActionData.asyncActionId}]",
                        onError: { hasError = true }])
                })

                if (hasError) {
                    asyncActionService.setAsError(asyncActionData.asyncActionId)
                } else {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                }
            }, [logErrorMessage: "CustomerExternalAuthorizationRequestConfigReplicationService.start >> Erro ao replicar informação para conta filha contas filhas. [AsyncActionID: ${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void saveAsyncActions(Long accountOwnerId, CustomerExternalAuthorizationRequestConfigType configType) {
        List<Long> childAccountIdList = Customer.notDisabledAccounts([column: "id", accountOwnerId: accountOwnerId]).list()

        List<Promise> promiseList = []

        for (List idList : childAccountIdList.collate(MAX_ITEMS_PER_ASYNC_ACTION)) {
            List idListTask = idList.collect()

            promiseList << task {
                asyncActionService.saveChildAccountExternalAuthorizationRequestConfigReplication([accountOwnerId: accountOwnerId, type: configType, childAccountIdList: idListTask])
            }
        }

        waitAll(promiseList)
    }

    public void replicateAllForChildAccount(Long accountOwnerId, Long childAccountId)  {
        List<CustomerExternalAuthorizationRequestConfig> configs = CustomerExternalAuthorizationRequestConfig.query([customerId: accountOwnerId]).list()
        for (CustomerExternalAuthorizationRequestConfig config : configs) {
            replicateConfigForChildAccount(config, childAccountId)
        }
    }

    private void replicateForChildAccount(Long accountOwnerId, Long childAccountId, CustomerExternalAuthorizationRequestConfigType configType) {
        CustomerExternalAuthorizationRequestConfig config = CustomerExternalAuthorizationRequestConfig.query([customerId: accountOwnerId, type: configType]).get()
        replicateConfigForChildAccount(config, childAccountId)
    }

    private void replicateConfigForChildAccount(CustomerExternalAuthorizationRequestConfig accountOwnerConfig, Long childAccountId) {
        Map fields = [:]

        fields.email = accountOwnerConfig.email
        fields.url = accountOwnerConfig.url
        fields.type = accountOwnerConfig.type
        fields.enabled = accountOwnerConfig.enabled
        fields.authToken = accountOwnerConfig.getDecryptedAccessToken()
        fields.forceAuthorization = accountOwnerConfig.forceAuthorization

        CustomerExternalAuthorizationRequestConfig childAccountConfig = CustomerExternalAuthorizationRequestConfig.query([customerId: childAccountId, type: accountOwnerConfig.type]).get()

        if (childAccountConfig) {
            childAccountConfig = customerExternalAuthorizationRequestConfigService.update(childAccountConfig, new CustomerExternalAuthorizationRequestConfigUpdateAdapter(fields))
        } else {
            Customer childAccount = Customer.read(childAccountId)
            childAccountConfig = customerExternalAuthorizationRequestConfigService.save(new CustomerExternalAuthorizationRequestConfigSaveAdapter(childAccount, fields))
        }

        if (childAccountConfig.hasErrors()) {
            throw new ValidationException(null, childAccountConfig.errors)
        }
    }

    private List<Long> listCustomersWithoutConfigOrOutdatedConfig(Long accountOwnerId, Map search) {
        CustomerExternalAuthorizationRequestConfig config = CustomerExternalAuthorizationRequestConfig.query([customerId: accountOwnerId, type: search.configType]).get()

        List<Long> customerIdList = []

        List<Long> customerWithoutConfigIdList = Customer.createCriteria().list() {
            projections {
                property("id")
            }

            "in"("id", search."customerId[in]".collect { Utils.toLong(it) })

            notExists CustomerExternalAuthorizationRequestConfig.where {
                setAlias("customerExternalAuthorizationRequestConfig")
                eqProperty("customerExternalAuthorizationRequestConfig.customer.id", "this.id")

                eq("customerExternalAuthorizationRequestConfig.type", search.configType)
            }.id()
        }

        customerIdList.addAll(customerWithoutConfigIdList)

        List<Long> customerWithOutdatedConfigIdList = Customer.createCriteria().list() {
            projections {
                property("id")
            }

            "in"("id", search."customerId[in]".collect { Utils.toLong(it) })

            exists CustomerExternalAuthorizationRequestConfig.where {
                setAlias("customerExternalAuthorizationRequestConfig")
                eqProperty("customerExternalAuthorizationRequestConfig.customer.id", "this.id")

                eq("customerExternalAuthorizationRequestConfig.type", search.configType)
                le("customerExternalAuthorizationRequestConfig.lastUpdated", config.lastUpdated)
            }.id()
        }

        customerIdList.addAll(customerWithOutdatedConfigIdList)

        return customerIdList.unique()
    }
}
