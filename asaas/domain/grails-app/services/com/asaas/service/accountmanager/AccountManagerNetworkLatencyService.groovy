package com.asaas.service.accountmanager

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.AccountManagerWorkingCallCenterManager
import com.asaas.log.AsaasLogger
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class AccountManagerNetworkLatencyService {

    public void save(Long accountManagerId, Integer roundTripTime) {
        if (!AsaasEnvironment.isProduction()) return
        BusinessValidation businessValidation = validateSave(accountManagerId, roundTripTime)

        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())
        AccountManagerWorkingCallCenterManager accountManagerWorkingCallCenterManager = new AccountManagerWorkingCallCenterManager()
        accountManagerWorkingCallCenterManager.saveAccountManagerNetworkLatency(accountManagerId, roundTripTime)
        Map result = accountManagerWorkingCallCenterManager.getResult()
        if (!result) AsaasLogger.error("AccountManagerWorkingCallCenterManager.saveAccountManagerNetworkLatency >> Erro ao salvar as evidências de conexão do gerente de contas com id ${accountManagerId}.")
    }

    private BusinessValidation validateSave(Long accountManagerId, Integer roundTripTime) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!accountManagerId) {
            businessValidation.addError("default.null.message", "Identificador do gerente de contas")
            return businessValidation
        }

        if (!roundTripTime) {
            businessValidation.addError("default.null.message", "Tempo de resposta da conexão")
            return businessValidation
        }

        return businessValidation
    }
}
