package com.asaas.service.criticalaction

import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CriticalActionExpirationService {

    def criticalActionService

    public void processExpiredForBill() {
        List<Long> criticalActionIdList = CriticalAction.pendingOrAwaitingAuthorization([column: "id", ignoreCustomer: true, bill: [scheduleDate: new Date().clearTime()]]).list()

        for (Long criticalActionId : criticalActionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CriticalAction action = CriticalAction.get(criticalActionId)
                criticalActionService.expire(action)
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.processExpiredForBill >> Erro no processamento de evento crítico expirado [criticalActiondId: ${criticalActionId}]."])
        }
    }

    public void processExpiredForScheduledPixDebit() {
        Date scheduledPixDebitAuthorizationDeadline = new Date().clearTime()
        List<Long> criticalActionIdList = CriticalAction.pendingOrAwaitingAuthorization([column: "id", ignoreCustomer: true, pixTransactionScheduledDate: scheduledPixDebitAuthorizationDeadline]).list()

        for (Long criticalActionId : criticalActionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CriticalAction action = CriticalAction.get(criticalActionId)
                criticalActionService.expire(action)
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.processExpiredForScheduledPixDebit >> Erro no processamento de evento crítico expirado [criticalActiondId: ${criticalActionId}]."])
        }
    }

}
