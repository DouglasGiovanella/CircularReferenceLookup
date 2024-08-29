package com.asaas.service.bifrost

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager

import grails.transaction.Transactional

@Transactional
class BifrostCardBillSandboxManagerService {

    public void closeBill(Long cardBillId) {
        if (!AsaasEnvironment.isDevelopment() || !BifrostManager.isEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/closeBill", ["cardBillId": cardBillId])
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void updateBillDueDateToToday(Long cardBillId) {
        if (!AsaasEnvironment.isDevelopment() || !BifrostManager.isEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/updateBillDueDateToToday", ["cardBillId": cardBillId])
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public void createRevolvingCredit(Long cardBillId) {
        if (!AsaasEnvironment.isDevelopment() || !BifrostManager.isEnabled()) throw new RuntimeException("Não foi possível executar operação!")

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/sandbox/createRevolvingCredit", ["cardBillId": cardBillId])
        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }
}
