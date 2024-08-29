package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.asyncAction.NegotiatedMccFeeConfigReplicationAsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class NegotiatedMccFeeConfigReplicationAsyncActionService {

    def baseAsyncActionService

    public List<Map> listPendingAndScheduled() {
        final Integer maxItemsPerCycle = 50

        Map search = [
            status: AsyncActionStatus.PENDING,
            sort: "id",
            order: "asc",
            "nextAttempt[le]": new Date()
        ]

        List<NegotiatedMccFeeConfigReplicationAsyncAction> asyncActionList = NegotiatedMccFeeConfigReplicationAsyncAction.query(search).list(max: maxItemsPerCycle, readOnly: true)
        return asyncActionList.collect { it.getDataAsMap() }
    }

    public void scheduleNextAttemptIfPossible(Long childAccountId, Long asyncActionId) {
        final Integer maxAsyncActionAttempts = 3

        NegotiatedMccFeeConfigReplicationAsyncAction asyncAction = NegotiatedMccFeeConfigReplicationAsyncAction.get(asyncActionId)
        asyncAction.attempts++

        if (asyncAction.attempts >= maxAsyncActionAttempts) {
            asyncAction.status = AsyncActionStatus.CANCELLED
            asyncAction.save(failOnError: true)

            AsaasLogger.warn("NegotiatedMccFeeConfigReplicationAsyncActionService.scheduleNextAttemptIfPossible >> Número máximo de attempts atingido, asyncAction não executará mais. [asyncActionId: ${asyncActionId}].")
            return
        }

        Map actionData = [childAccountId: childAccountId]
        Integer nextAttemptIntervalInMinutes = (2 ** asyncAction.attempts) * 30
        asyncAction.nextAttempt = CustomDateUtils.sumMinutes(new Date(), nextAttemptIntervalInMinutes)

        baseAsyncActionService.save(asyncAction, actionData)
    }

    public void setAsErrorWithNewTransaction(Long id) {
        AsyncAction.withNewTransaction {
            NegotiatedMccFeeConfigReplicationAsyncAction asyncAction = NegotiatedMccFeeConfigReplicationAsyncAction.get(id)
            asyncAction.status = AsyncActionStatus.ERROR
            asyncAction.save(failOnError: true)
        }
    }

    public void save(Long childAccountId) {
        NegotiatedMccFeeConfigReplicationAsyncAction asyncAction = new NegotiatedMccFeeConfigReplicationAsyncAction()
        Map actionData = [childAccountId: childAccountId]

        if (baseAsyncActionService.hasAsyncActionPendingWithSameParameters(NegotiatedMccFeeConfigReplicationAsyncAction, actionData)) return

        baseAsyncActionService.save(asyncAction, actionData)
    }
}
