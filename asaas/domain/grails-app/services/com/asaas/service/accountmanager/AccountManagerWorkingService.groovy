package com.asaas.service.accountmanager

import com.asaas.domain.accountmanager.AccountManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class AccountManagerWorkingService {

    public String generateAccessToken(Long accountManagerId) {
        AccountManager accountManager = AccountManager.get(accountManagerId)

        String accessToken = UUID.randomUUID()
        Calendar expiration = CustomDateUtils.getInstanceOfCalendar()
        expiration.add(Calendar.HOUR, 12)

        accountManager.accessToken = accessToken
        accountManager.accessTokenExpiration = expiration.getTime()
        accountManager.save(failOnError: true)

        return accessToken
    }

    public AccountManager validateAccessToken(Long accountManagerId, String accessToken, Map paramsToLogError = null) {
        if (!accountManagerId || !accessToken) {
            AsaasLogger.error("${paramsToLogError.controller}.${paramsToLogError.action} -> validateAccessToken >> Erro ao validar token. [accountManagerId: ${paramsToLogError.accountManagerId}]")
        }

        AccountManager validatedAccountManager = AccountManager.get(accountManagerId)

        if (validatedAccountManager.accessToken != accessToken) {
            DomainUtils.addError(validatedAccountManager, "Seu token de acesso é inválido. Renove-o logando novamente no Asaas.")
            return validatedAccountManager
        }

        if (validatedAccountManager.accessTokenExpiration < new Date()) {
            DomainUtils.addError(validatedAccountManager, "Seu token de acesso expirou. Renove-o logando novamente no Asaas.")
            return validatedAccountManager
        }

        return validatedAccountManager
    }
}
