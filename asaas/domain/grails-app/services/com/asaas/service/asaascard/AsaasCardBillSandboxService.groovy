package com.asaas.service.asaascard

import com.asaas.integration.bifrost.api.BifrostManager

import grails.transaction.Transactional

@Transactional
class AsaasCardBillSandboxService {

    def bifrostCardBillSandboxManagerService

    public void closeBill(Long cardBillId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")
        bifrostCardBillSandboxManagerService.closeBill(cardBillId)
    }

    public void updateBillDueDate(Long cardBillId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")
        bifrostCardBillSandboxManagerService.updateBillDueDateToToday(cardBillId)
    }

    public void createRevolvingCredit(Long cardBillId) {
        if (!BifrostManager.hasSandboxActionsEnabled()) throw new RuntimeException("Não foi possível executar operação!")
        bifrostCardBillSandboxManagerService.createRevolvingCredit(cardBillId)
    }
}
