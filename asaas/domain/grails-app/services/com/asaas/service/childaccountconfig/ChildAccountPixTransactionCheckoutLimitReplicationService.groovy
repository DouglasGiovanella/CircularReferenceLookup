package com.asaas.service.childaccountconfig

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ChildAccountPixTransactionCheckoutLimitReplicationService {

    def asyncActionService
    def childAccountParameterService
    def pixTransactionCheckoutLimitParameterService

    public void start() {
        for (Map asyncActionData : asyncActionService.listPendingChildAccountPixTransactionCheckoutLimitReplication()) {
            Utils.withNewTransactionAndRollbackOnError({
                Long accountOwnerId = asyncActionData.accountOwnerId
                String name = asyncActionData.name
                ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: PixTransactionCheckoutLimit.simpleName, name: name]).get()

                applyParameter(childAccountParameter, accountOwnerId)

                if (!hasCustomerToReplicate(childAccountParameter, accountOwnerId)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "ChildAccountPixTransactionCheckoutLimitReplicationService.start >> Erro no processamento da replicação das taxas de PixTransactionCheckoutLimit [${asyncActionData.name}] para as contas filhas. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyParameter(ChildAccountParameter childAccountParameter, Long accountOwnerId) {
        final Integer maximumNumberOfDataPerReplication = 2000

        List<Long> customerIdList = Customer.createCriteria().list(max: maximumNumberOfDataPerReplication, buildCriteriaForChildAccountsWithoutConfig(childAccountParameter, accountOwnerId))

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Customer childAccount = Customer.get(customerId)
            pixTransactionCheckoutLimitParameterService.applyParameter(childAccount, childAccountParameter)
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
                notExists PixTransactionCheckoutLimit.where {
                    setAlias("pixTransactionCheckoutLimit")

                    eqProperty("pixTransactionCheckoutLimit.customer.id", "this.id")
                    eq("pixTransactionCheckoutLimit.${childAccountParameter.name}", Utils.toBigDecimal(childAccountParameter.value))
                }.id()

                not {
                    exists PixTransactionCheckoutLimit.where {
                        setAlias("pixTransactionCheckoutLimit")

                        eqProperty("pixTransactionCheckoutLimit.customer.id", "this.id")
                    }.id()
                }
            }
        }
    }
}
