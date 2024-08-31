package com.asaas.service.sage

import com.asaas.exception.BusinessException
import com.asaas.integration.sage.SageManager
import com.asaas.integration.sage.dto.authentication.SageAdminAuthenticationRequestTokenDTO
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils

import grails.transaction.Transactional

@Transactional
class SageAuthenticationService {

    public String requestAuthenticationToken() {
        validateUser()

        SageManager sageManager = new SageManager()

        String username = UserUtils.getCurrentUser().username
        sageManager.post("/asaas-admin-authentication/requestToken", new SageAdminAuthenticationRequestTokenDTO(username).properties)

        if (!sageManager.isSuccessful()) {
            String errorMessage = sageManager.getErrorMessage()
            AsaasLogger.error("${this.class.getSimpleName()}.requestAuthenticationToken >> Ocorreu um erro ao requisitar uma chave de acesso do Sage. [Usuário: ${username} - ${errorMessage}")
            throw new BusinessException(errorMessage)
        }

        return sageManager.responseBody.authToken
    }

    private void validateUser() {
        if (!UserUtils.currentUserIsAsaasTeam()) {
            AsaasLogger.warn("${this.class.getSimpleName()}.validateUser >> Tentativa de acesso com tipo de usuário inválido. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Não foi possível autenticar o usuário")
        }

        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.ACCESS_SAGE)) {
            AsaasLogger.warn("${this.class.getSimpleName()}.validateUser >> Tentativa de acesso com usuário sem permissão. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Não foi possível autenticar o usuário")
        }
    }
}
