package com.asaas.service.childaccountconfig

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerAttributesReplicationService {

    def asyncActionService
    def customerAttributesParameterService

    public void start() {
        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_ATTRIBUTES_REPLICATION, null)) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: Customer.simpleName, name: name]).get()

                applyParameter(childAccountParameter, accountOwnerId)

                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerAttributesReplicationService.start >> Erro no processamento da replicação do ${asyncActionData.name} para as contas filhas do customer ${asyncActionData.accountOwnerId}. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        List<Long> customerIdList = listChildAccountsToApplyConfigIfNecessary(childAccountParameter, accountOwnerId)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
            Customer childAccount = Customer.get(customerId)
            customerAttributesParameterService.applyParameter(childAccount, childAccountParameter)
            })
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        return listChildAccountsToApplyConfigIfNecessary(childAccountParameter, accountOwnerId).asBoolean()
    }

    private List<Customer> listChildAccountsToApplyConfigIfNecessary(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerMigration = 2000

        Object parsedValue = customerAttributesParameterService.parseCustomerAttributesConfigValueForApply(childAccountParameter.name, childAccountParameter.value)

        Map search = [column: "id", accountOwnerId: accountOwnerId]
        search."${childAccountParameter.name}[ne]" = parsedValue

        return Customer.query(search).list(max: maximumNumberOfDataPerMigration)
    }
}
