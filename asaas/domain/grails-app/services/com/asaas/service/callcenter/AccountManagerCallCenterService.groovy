package com.asaas.service.callcenter

import com.asaas.domain.accountmanager.AccountManager
import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.AccountManagerCallCenterManager
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class AccountManagerCallCenterService {

    def grailsApplication

    public void create(AccountManager accountManager) {
        if (grailsApplication.config.callcenter.accountManager.testMode) {
            AsaasLogger.warn("AccountManagerCallCenterService.create >> Dados não enviados ao Call Center devido o testMode estar ativo")
            return
        }

        AccountManagerCallCenterManager accountManagerCallCenterManager = new AccountManagerCallCenterManager()
        accountManagerCallCenterManager.create(accountManager)

        if (!accountManagerCallCenterManager.getResult()) AsaasLogger.error("AccountManagerCallCenterService.create >> Erro ao criar gerente de contas no Call Center")
    }

    public void update(AccountManager accountManager) {
        if (grailsApplication.config.callcenter.accountManager.testMode) {
            AsaasLogger.warn("AccountManagerCallCenterService.update >> Dados não enviados ao Call Center devido o testMode estar ativo")
            return
        }

        AccountManagerCallCenterManager accountManagerCallCenterManager = new AccountManagerCallCenterManager()
        accountManagerCallCenterManager.update(accountManager)

        Map responseBodyMap = accountManagerCallCenterManager.responseBodyMap
        if (!responseBodyMap.success) throw new BusinessException(responseBodyMap.message)
    }

    public void disable(AccountManager accountManager) {
        if (grailsApplication.config.callcenter.accountManager.testMode) {
            AsaasLogger.warn("AccountManagerCallCenterService.disable >> Dados não enviados ao Call Center devido o testMode estar ativo")
            return
        }

        AccountManagerCallCenterManager accountManagerCallCenterManager = new AccountManagerCallCenterManager()
        accountManagerCallCenterManager.disable(accountManager)

        if (!accountManagerCallCenterManager.getResult()) AsaasLogger.error("AccountManagerCallCenterService.disable >> Erro ao desativar gerente de contas no Call Center")
    }

    public void disableTraining(Long accountManagerId) {
        if (grailsApplication.config.callcenter.accountManager.testMode) {
            AsaasLogger.warn("AccountManagerCallCenterService.disableTraining >> Dados não enviados ao Call Center devido o testMode estar ativo")
            return
        }

        AccountManagerCallCenterManager accountManagerCallCenterManager = new AccountManagerCallCenterManager()
        accountManagerCallCenterManager.disableTraining(accountManagerId)
        if (!accountManagerCallCenterManager.getResult()) throw new BusinessException("Erro ao desabilitar treinamento do gerente no call center.")
    }

    public void changeAccountManagerAttendanceType(Long accountManagerId, String attendanceType) {
        if (grailsApplication.config.callcenter.accountManager.testMode) {
            AsaasLogger.warn("AccountManagerCallCenterService.changeAccountManagerAttendanceType >> Dados não enviados ao Call Center devido o testMode estar ativo")
            return
        }

        AccountManagerCallCenterManager accountManagerCallCenterManager = new AccountManagerCallCenterManager()
        accountManagerCallCenterManager.changeAttendanceType(accountManagerId, attendanceType)
        if (!accountManagerCallCenterManager.getResult()) throw new BusinessException("Erro ao atualizar o tipo de atendimento do gerente no call center.")
    }
}
