package com.asaas.service.accountsecurityevent

import com.asaas.domain.user.UserPasswordHistory
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.adapter.accountsecurityevent.AccountSecurityEventRequestAdapter
import com.asaas.integration.sauron.enums.accountsecurityevent.AccountSecurityEventType
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class AccountSecurityEventService {

    def accountSecurityEventAsyncActionService

    public void save(UserPasswordHistory userPasswordHistory) {
        if (!AsaasEnvironment.isProduction()) return

        try {
            AccountSecurityEventRequestAdapter adapter = new AccountSecurityEventRequestAdapter(userPasswordHistory)
            accountSecurityEventAsyncActionService.save(adapter)
        } catch (Exception exception) {
            AsaasLogger.error("AccountSecurityEventService.save >> Ocorreu um erro ao salvar asyncAction do evento de segurança: ResourceDomainName [${userPasswordHistory?.getClass()}] ResourceDomainId [${userPasswordHistory?.id}] AccountSecurityEventType [${AccountSecurityEventType.USER_PASSWORD_UPDATED.toString()}]", exception)
        }
    }
}
