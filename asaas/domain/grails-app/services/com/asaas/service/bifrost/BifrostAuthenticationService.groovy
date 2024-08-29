package com.asaas.service.bifrost

import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils

import grails.transaction.Transactional

@Transactional
class BifrostAuthenticationService {

    public String requestAuthenticationToken() {
        validateRequestAuthenticationToken()

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/authentication/request", [username: UserUtils.getCurrentUser().username])

        if (!bifrostManager.isSuccessful()) {
            AsaasLogger.error("BifrostCardService -> O seguinte erro foi retornado ao gerar chave de acesso [username: ${UserUtils.getCurrentUser().username}]: ${bifrostManager.getErrorMessage()}")
            throw new BusinessException(bifrostManager.getErrorMessage())
        }

        return bifrostManager.responseBody.authToken
    }

    private void validateRequestAuthenticationToken() {
        if (!BifrostManager.isEnabled()) {
            AsaasLogger.warn("BifrostAuthenticationService -> Tentativa de acesso inválida. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado.")
        }

        if (!UserUtils.currentUserIsAsaasTeam()) {
            AsaasLogger.warn("BifrostAuthenticationService -> Tentativa de acesso com tipo de usuário inválido. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado.")
        }

        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.ACCESS_BIFROST)) {
            AsaasLogger.warn("BifrostAuthenticationService -> Tentativa de acesso com usuário sem. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado.")
        }
    }
}
