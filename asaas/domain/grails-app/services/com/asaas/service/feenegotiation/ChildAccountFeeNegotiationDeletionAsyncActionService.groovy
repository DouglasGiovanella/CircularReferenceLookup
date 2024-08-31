package com.asaas.service.feenegotiation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ChildAccountFeeNegotiationDeletionAsyncActionService {

    def asyncActionService
    def feeNegotiationDeletionAsyncActionService

    public void process() {
        Map asyncActionInfo = asyncActionService.getPending(AsyncActionType.APPLY_DEFAULT_FEE_CONFIG_TO_CHILD_ACCOUNTS_FOR_NEGOTIATION_DELETION)
        if (!asyncActionInfo) return

        FeeNegotiationProduct product = FeeNegotiationProduct.valueOf(asyncActionInfo.product.toString())
        Long accountOwnerId = Long.valueOf(asyncActionInfo.accountOwnerId.toString())
        Long lastChildAccountProcessedId = Utils.toLong(asyncActionInfo.lastChildAccountProcessedId)
        final Integer maxItemsPerCycle = 2000

        List<Long> childAccountIdList = Customer.query(buildSearch(accountOwnerId, lastChildAccountProcessedId)).list(max: maxItemsPerCycle)

        for (Long childAccountId : childAccountIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                feeNegotiationDeletionAsyncActionService.apply(childAccountId, product)
            }, [logErrorMessage: "ChildAccountFeeNegotiationDeletionAsyncActionService.process >> Erro ao remover negociação de taxa da conta filha. [asyncActionId: ${asyncActionInfo.asyncActionId}, childAccountId: ${childAccountId}, prodcut: ${product}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionInfo.asyncActionId) }])
        }

        asyncActionService.delete(asyncActionInfo.asyncActionId)

        if (!childAccountIdList) return

        saveNextAsyncAction(accountOwnerId, product, childAccountIdList.last())
    }

    private Map buildSearch(Long accountOwnerId, Long lastChildAccountProcessedId) {
        Map search = [:]
        search.accountOwnerId = accountOwnerId
        search.column = "id"
        search.order = "asc"

        if (!lastChildAccountProcessedId) return search

        search."id[gt]" = lastChildAccountProcessedId

        return search
    }

    private void saveNextAsyncAction(Long accountOwnerId, FeeNegotiationProduct product, Long lastChildAccountProcessedId) {
        Map actionData = [product: product, accountOwnerId: accountOwnerId, lastChildAccountProcessedId: lastChildAccountProcessedId]
        asyncActionService.save(AsyncActionType.APPLY_DEFAULT_FEE_CONFIG_TO_CHILD_ACCOUNTS_FOR_NEGOTIATION_DELETION, actionData)
    }
}
