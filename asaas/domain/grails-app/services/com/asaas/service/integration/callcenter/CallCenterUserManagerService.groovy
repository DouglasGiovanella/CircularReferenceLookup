package com.asaas.service.integration.callcenter

import com.asaas.domain.user.User
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.internaluser.dto.CreateUserRequestDTO
import com.asaas.internaluser.dto.DeleteUserRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.userpermission.AdminUserPermissionName

import grails.transaction.Transactional

@Transactional
class CallCenterUserManagerService {

    def grailsApplication

    public void save(User user, List<AdminUserPermissionName> adminUserPermissionNameList, User currentUser) {
        if (grailsApplication.config.callcenter.internalUser.testMode) return

        CreateUserRequestDTO createUserRequestDTO = new CreateUserRequestDTO(user, currentUser, adminUserPermissionNameList)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/api/user", createUserRequestDTO)

        if (!callCenterManager.isSuccessful()) AsaasLogger.error("CallCenterUserManagerService.save >> Não foi possível salvar o usuário no callcenter. ${callCenterManager.getErrorMessage()}")
    }

    public void delete(User user, User currentUser) {
        if (grailsApplication.config.callcenter.internalUser.testMode) return

        DeleteUserRequestDTO deleteUserRequestDTO = new DeleteUserRequestDTO(user.id, currentUser)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/api/user/delete", deleteUserRequestDTO)

        if (!callCenterManager.isSuccessful()) AsaasLogger.error("CallCenterUserManagerService.delete >> Não foi possível remover o usuário do callcenter. ${callCenterManager.getErrorMessage()}")
    }
}
