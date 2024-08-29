package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerFeeReplicationService {

    def asyncActionService
    def customerFeeParameterService
    def childAccountParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountCustomerFeeReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerFee.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)
                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerFeeReplicationService.start >> Erro no processamento da replicação das taxas de CustomerFee [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, childAccountParameter.name, childAccountParameter.value, childAccountParameter.type)

        final Integer maximumNumberOfDataPerReplication = 2000
        List<Long> customerIdList = CustomerFee.query(search).list(max: maximumNumberOfDataPerReplication)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerFeeParameterService.applyParameter(childAccount, childAccountParameter)
        })
    }

    private Boolean hasCustomerToReplicate(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        Map search = childAccountParameterService.buildQueryParamsForReplication(accountOwnerId, childAccountParameter.name, childAccountParameter.value, childAccountParameter.type)
        search.exists = true
        return CustomerFee.query(search).get().asBoolean()
    }
}
