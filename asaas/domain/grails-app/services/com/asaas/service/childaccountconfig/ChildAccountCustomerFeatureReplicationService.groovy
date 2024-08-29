package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountCustomerFeatureReplicationService {

    def asyncActionService
    def customerFeatureParameterService

    public void start() {
        for (Map asyncActionData : asyncActionService.listPendingChildAccountCustomerFeatureReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerFeature.simpleName, name: name]).get()
                applyParameter(childAccountParameter, accountOwnerId)

                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountCustomerFeatureReplicationService.start >> Erro no processamento da replicação da tokenização de cartão para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerMigration = 2000

        List<Long> customerIdList = Customer.createCriteria().list(max: maximumNumberOfDataPerMigration, buildCriteriaForChildAccountsWithoutConfig(childAccountParameter, accountOwnerId))
        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            customerFeatureParameterService.applyParameter(childAccount, childAccountParameter)
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

            notExists CustomerFeature.where {
                setAlias("customerFeature")

                eqProperty("customerFeature.customer.id", "this.id")
                eq("customerFeature.canHandleBillingInfo", Boolean.valueOf(childAccountParameter.value))
            }.id()
        }
    }
}
