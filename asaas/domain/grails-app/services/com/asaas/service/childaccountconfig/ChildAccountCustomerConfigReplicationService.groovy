package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerConfigReplicationService {

    def asyncActionService
    def customerConfigParameterService
    def childAccountParameterService

    public void start() {
        for (Map asyncActionData : asyncActionService.listPendingChildAccountCustomerConfigReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerConfig.simpleName, name: name]).get()

                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerConfigReplicationService.start >> Erro no processamento da replicação do ${asyncActionData.name} para as contas filhas do customer ${asyncActionData.accountOwnerId}. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerMigration = 2000

        List<Long> customerIdList = Customer.createCriteria().list(max: maximumNumberOfDataPerMigration, buildCriteriaForChildAccountsWithoutConfig(childAccountParameter, accountOwnerId))

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerConfigParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        return Customer.createCriteria().list(buildCriteriaForChildAccountsWithoutConfig(childAccountParameter, accountOwnerId)).asBoolean()
    }

    private Closure buildCriteriaForChildAccountsWithoutConfig(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        return {
            projections {
                property("id")
            }

            eq("deleted", false)
            eq("accountOwner.id", accountOwnerId)

            notExists CustomerConfig.where {
                setAlias("customerConfig")

                eqProperty("customerConfig.customer.id", "this.id")
                eq("customerConfig.${childAccountParameter.name}", customerConfigParameterService.parseParameterValue(childAccountParameter.name, childAccountParameter.value))
            }.id()
        }
    }
}
