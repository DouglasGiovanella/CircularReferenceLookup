package com.asaas.service.ocean

import com.asaas.exception.BusinessException
import com.asaas.integration.ocean.OceanManager
import com.asaas.integration.ocean.dto.authentication.OceanAdminAuthenticationRequestTokenDTO
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils
import grails.transaction.Transactional

@Transactional
class OceanAuthenticationService {

    public String requestAuthenticationToken() {
        validateUser()

        OceanManager oceanManager = new OceanManager()

        String username = UserUtils.getCurrentUser().username
        oceanManager.post("/asaas-admin-authentication/requestToken", new OceanAdminAuthenticationRequestTokenDTO(username).properties)

        if (!oceanManager.isSuccessful()) {
            String errorMessage = oceanManager.getErrorMessage()
            AsaasLogger.error("${this.class.getSimpleName()}.requestAuthenticationToken >> Ocorreu um erro ao requisitar uma chave de acesso do Ocean. [Usuário: ${username} - ${errorMessage}")
            throw new BusinessException(errorMessage)
        }

        return oceanManager.responseBody.authToken
    }

    private void validateUser() {
        if (!UserUtils.currentUserIsAsaasTeam()) {
            AsaasLogger.warn("${this.class.getSimpleName()}.validateUser >> Tentativa de acesso com tipo de usuário inválido. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Não foi possível autenticar o usuário")
        }

        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.ACCESS_OCEAN)) {
            AsaasLogger.warn("${this.class.getSimpleName()}.validateUser >> Tentativa de acesso com usuário sem permissão. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Não foi possível autenticar o usuário")
        }
    }
}
