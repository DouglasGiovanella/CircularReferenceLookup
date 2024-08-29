package com.asaas.service.customerdealinfo.creditcarddynamicmcc

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.domain.asyncAction.NegotiatedMccFeeConfigReplicationAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class NewChildAccountCreditCardDynamicMccFeeConfigReplicationService {

    def customerDealInfoCreditCardDynamicMccFeeConfigReplicationService
    def negotiatedMccFeeConfigReplicationAsyncActionService

    public void saveReplicationIfPossible(Customer customer) {
        if (!customer.accountOwnerId) return
        if (!accountOwnerHasDynamicMccFeeNegotiation(customer.accountOwnerId)) return

        negotiatedMccFeeConfigReplicationAsyncActionService.save(customer.id)
    }

    public void replicate() {
        for (Map asyncActionData : negotiatedMccFeeConfigReplicationAsyncActionService.listPendingAndScheduled()) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer childAccount = Customer.get(asyncActionData.childAccountId)
                Customer accountOwner = childAccount.accountOwner

                Boolean childAccountHasMcc = childAccount.getMcc().asBoolean()
                if (!childAccountHasMcc) {
                    negotiatedMccFeeConfigReplicationAsyncActionService.scheduleNextAttemptIfPossible(childAccount.id, asyncActionData.asyncActionId)
                    return
                }

                NegotiatedMccFeeConfigReplicationAsyncAction asyncAction = NegotiatedMccFeeConfigReplicationAsyncAction.get(asyncActionData.asyncActionId)
                Boolean shouldReplicate = customerDealInfoCreditCardDynamicMccFeeConfigReplicationService.shouldReplicateToChildAccount(accountOwner, childAccount)

                if (shouldReplicate) {
                    customerDealInfoCreditCardDynamicMccFeeConfigReplicationService.replicateToChildAccountIfPossible(accountOwner, childAccount)
                }

                asyncAction.delete(failOnError: true)
            }, [logErrorMessage: "NewChildAccountCreditCardDynamicMccFeeConfigReplicationService.replicate >> Erro ao replicar taxas de Cartão de Crédito com MCC dinâmico para novas contas filhas. AsyncActionId: [${asyncActionData.asyncActionId}]",
                onError: { negotiatedMccFeeConfigReplicationAsyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private Boolean accountOwnerHasDynamicMccFeeNegotiation(Long accountOwnerId) {
        if (!accountOwnerId) return false

        Boolean hasNegotiation = CustomerDealInfoFeeConfigGroupRepository.query(["customerId": accountOwnerId, "replicationType": FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC]).exists()
        if (!hasNegotiation) return false

        return true
    }
}
