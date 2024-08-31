package com.asaas.service.integration.asaasMoney

import com.asaas.exception.BusinessException
import com.asaas.integration.asaasMoney.api.AsaasMoneyManager
import com.asaas.integration.asaasMoney.dto.AsaasMoneyAdminAuthenticationTokenRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.AdminUserPermissionUtils
import grails.transaction.Transactional

@Transactional
class AsaasMoneyAdminService {

    public String getAdminAuthenticationToken() {
        validateUser()
        AsaasMoneyManager asaasMoneyManager = new AsaasMoneyManager()
        asaasMoneyManager.post("asaas-admin-authentication/token", new AsaasMoneyAdminAuthenticationTokenRequestDTO(UserUtils.getCurrentUser().username).properties)

        if (!asaasMoneyManager.isSuccessful()) {
            AsaasLogger.error("AsaasMoneyManagerService.getAuthenticationToken >> Erro ao disparar request de obtenção de token para autenticação do usuário ${UserUtils.getCurrentUser().username}: ${asaasMoneyManager.getErrorMessage()}")
            throw new RuntimeException(asaasMoneyManager.getErrorMessage())
        }

        return asaasMoneyManager.responseBody.authToken
    }

    public String buildUrlParams(String authToken, Map params) {
        params.remove("controller")
        params.remove("format")
        params.remove("action")

        Map urlParamsMap = params + [authToken: authToken]
        String urlParamsString = ""
        urlParamsMap.each { key, value -> urlParamsString += "$key=$value&" }

        return urlParamsString[0..-2]
    }

    public void validateUser() {
        if (!UserUtils.currentUserIsAsaasTeam()) {
            AsaasLogger.warn("AsaasMoneyAdminService -> Tentativa de acesso com tipo de usuário inválido. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado!")
        }

        if (!AdminUserPermissionUtils.allowed(AdminUserPermissionName.ACCESS_ASAAS_MONEY)) {
            AsaasLogger.warn("AsaasMoneyAdminService -> Tentativa de acesso com usuário sem permissão. [Username: ${UserUtils.getCurrentUser().username}]")
            throw new BusinessException("Acesso negado!")
        }
    }
}
