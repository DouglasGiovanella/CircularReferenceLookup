package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.internalloan.InternalLoanConfig
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountInternalLoanConfigReplicationService {

    def asyncActionService
    def internalLoanConfigParameterService

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingChildAccountInternalLoanConfigReplication()) {
            Utils.withNewTransactionAndRollbackOnError ({
                Long accountOwnerId = asyncActionData.accountOwnerId
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: InternalLoanConfig.simpleName, name: "enabled"]).get()

                applyParameter(childAccountParameter, accountOwnerId)

                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountInternalLoanConfigReplicationService.start >> Erro na aplicação das configurações de avalista-devedor para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerMigration = 2000

        List<Long> customerIdList = Customer.createCriteria().list(max: maximumNumberOfDataPerMigration, buildCriteriaForChildAccountsWithoutConfig(childAccountParameter, accountOwnerId))

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            internalLoanConfigParameterService.applyParameter(childAccount, childAccountParameter)
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
            or {
                notExists InternalLoanConfig.where {
                    setAlias("internalLoanConfig")

                    eqProperty("internalLoanConfig.debtor.id", "this.id")
                }.id()
                exists InternalLoanConfig.where {
                    setAlias("internalLoanConfig")

                    eqProperty("internalLoanConfig.debtor.id", "this.id")
                    ne("internalLoanConfig.enabled", Boolean.valueOf(childAccountParameter.value))
                }.id()
            }
        }
    }
}
