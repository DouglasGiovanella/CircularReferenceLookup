package com.asaas.service.asyncaction

import com.asaas.domain.asyncAction.RecalculateBalanceAsyncAction
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class RecalculateBalanceAsyncActionService {

    def baseAsyncActionService
    def financialTransactionService
    def receivableAnticipationValidationCacheService

    public void saveIfNecessary(Long customerId) {
        Map actionData = [customerId: customerId]
        if (baseAsyncActionService.hasAsyncActionPendingWithSameParameters(RecalculateBalanceAsyncAction, actionData)) return

        baseAsyncActionService.save(new RecalculateBalanceAsyncAction(), actionData)
    }

    public Boolean process() {
        final Integer maxItemsPerCycle = 1000

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(RecalculateBalanceAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return false

        baseAsyncActionService.processListWithNewTransaction(RecalculateBalanceAsyncAction, asyncActionDataList, { Map asyncActionData ->
            financialTransactionService.recalculateBalance(Customer.read(asyncActionData.customerId))
        }, [shouldRetryOnErrorClosure: { Exception exception -> return true }])

        return true
    }
}
