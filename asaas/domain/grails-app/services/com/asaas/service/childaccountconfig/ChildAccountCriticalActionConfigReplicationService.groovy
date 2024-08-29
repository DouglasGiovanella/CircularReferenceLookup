package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCriticalActionConfigReplicationService {

    def asyncActionService
    def criticalActionConfigParameterService
    def childAccountParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountCriticalActionConfigReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerCriticalActionConfig.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCriticalActionConfigReplicationService.start >> Erro no processamento da replicação das configurações de CustomerCriticalActionConfig [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, childAccountParameter.name, childAccountParameter.value, childAccountParameter.type)

        final Integer maximumNumberOfDataPerMigration = 2000
        List<Long> customerIdList = CustomerCriticalActionConfig.query(search).list(max: maximumNumberOfDataPerMigration)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            criticalActionConfigParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, childAccountParameter.name, childAccountParameter.value, childAccountParameter.type)
        search.exists = true
        return CustomerCriticalActionConfig.query(search).get().asBoolean()
    }
}
