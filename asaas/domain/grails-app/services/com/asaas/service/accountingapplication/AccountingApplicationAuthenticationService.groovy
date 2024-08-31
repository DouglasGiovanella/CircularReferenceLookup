package com.asaas.service.accountingapplication

import com.asaas.exception.BusinessException
import com.asaas.integration.accountingapplication.AccountingApplicationManager
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class AccountingApplicationAuthenticationService {

    def userRolePermissionService

    public String requestAuthenticationToken() {
        validateRequestAuthenticationToken()

        AccountingApplicationManager accountingManager = new AccountingApplicationManager()
        accountingManager.getAuthenticationToken(UserUtils.getCurrentUser().username)

        if (!accountingManager.isSuccessful()) {
            AsaasLogger.error("AccountingApplicationAuthenticationService.requestAuthenticationToken >>> O Accounting retornou um status diferente de sucesso ao buscar o token privado. StatusCode: [${accountingManager.statusCode}], ResponseBody: [${accountingManager.responseBody}]")
            return null
        }

        return accountingManager.responseBody.authToken
    }

    private void validateRequestAuthenticationToken() {
        if (!UserUtils.currentUserIsAsaasTeam()) {
            AsaasLogger.warn("AccountingApplicationAuthenticationService.requestAuthenticationToken >>> Tentativa de acesso com tipo de usuário inválido. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado.")
        }

        if (!userRolePermissionService.canAccessAccountingApplication()) {
            AsaasLogger.warn("AccountingApplicationAuthenticationService.requestAuthenticationToken >>> Tentativa de acesso com usuário sem permissão. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado.")
        }
    }
}
