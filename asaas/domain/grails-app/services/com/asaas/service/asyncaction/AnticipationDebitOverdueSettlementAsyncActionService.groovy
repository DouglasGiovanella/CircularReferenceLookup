package com.asaas.service.asyncaction

import com.asaas.domain.asyncAction.AnticipationDebitOverdueSettlementAsyncAction
import grails.transaction.Transactional

@Transactional
class AnticipationDebitOverdueSettlementAsyncActionService {

    def baseAsyncActionService
    def receivableAnticipationValidationCacheService

    public void saveIfNecessary(Long customerId, Map actionData) {
        Boolean hasSettlementAwaitingCredit = receivableAnticipationValidationCacheService.isCustomerWithPartnerSettlementAwaitingCredit(customerId)
        if (!hasSettlementAwaitingCredit) return

        save(customerId, actionData)
    }

    private void save(Long customerId, Map actionData) {
        AnticipationDebitOverdueSettlementAsyncAction asyncAction = new AnticipationDebitOverdueSettlementAsyncAction()
        asyncAction.groupId = customerId

        baseAsyncActionService.save(asyncAction, actionData)
    }
}
