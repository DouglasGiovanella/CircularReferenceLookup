package com.asaas.service.childaccountconfig

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerTransferConfig
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerTransferReplicationService {

    def asyncActionService
    def childAccountParameterService
    def customerTransferConfigParameterService

    public void start() {
        List<Map> pendingChildAccountReplicationList = asyncActionService.listPending(AsyncActionType.CHILD_ACCOUNT_CUSTOMER_TRANSFER_CONFIG_REPLICATION, null)
        for (Map asyncActionData : pendingChildAccountReplicationList) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerTransferConfig.simpleName, name: name]).get()

                applyParameter(childAccountParameter, accountOwnerId)

                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerTransferReplicationService.start >> Erro no processamento da replicação das configurações de CustomerTransferConfig [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, childAccountParameter.name, childAccountParameter.value, childAccountParameter.type)

        final Integer maximumNumberOfDataPerReplication = 2000

        List<Long> customerIdList = CustomerTransferConfig.query(search).list(max: maximumNumberOfDataPerReplication)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerTransferConfigParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        String fieldName = childAccountParameter.name
        Object value = childAccountParameter.value

        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, fieldName, value, childAccountParameter.type)
        search.exists = true

        return CustomerTransferConfig.query(search).get().asBoolean()
    }
}
