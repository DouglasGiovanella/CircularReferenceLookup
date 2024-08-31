package com.asaas.service.asyncaction

import com.asaas.domain.accountsecurityevent.AccountSecurityEventAsyncAction
import com.asaas.integration.sauron.adapter.accountsecurityevent.AccountSecurityEventRequestAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AccountSecurityEventAsyncActionService {

    def baseAsyncActionService
    def sauronAccountSecurityEventManagerService

    public void save(AccountSecurityEventRequestAdapter adapter) {
        Map asyncActionData = adapter.toAsyncActionData()

        baseAsyncActionService.save(new AccountSecurityEventAsyncAction(), asyncActionData)
    }

    public void processAccountSecurityEvent() {
        final Integer maxItemsPerCycle = 1000

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(AccountSecurityEventAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return

        final Integer collateSize = 100

        asyncActionDataList.collate(collateSize).each { asyncActionBatch ->
            List<AccountSecurityEventRequestAdapter> accountSecurityEventRequestAdapterList = asyncActionBatch.collect { new AccountSecurityEventRequestAdapter(it) }
            List<Long> asyncActionIdList = asyncActionBatch.collect { actionData -> actionData.asyncActionId } as List<Long>

            Utils.withNewTransactionAndRollbackOnError({
                sauronAccountSecurityEventManagerService.saveList(accountSecurityEventRequestAdapterList)
                baseAsyncActionService.deleteList(AccountSecurityEventAsyncAction, asyncActionIdList)
            }, [onError: { Exception exception ->
                AsaasLogger.error("AccountSecurityEventAsyncActionService.processAccountSecurityEvent >> Ocorreu um erro ao processar os eventos de segurança", exception)
                baseAsyncActionService.setAsErrorWithNewTransaction(asyncActionIdList)
            }])
        }
    }
}
