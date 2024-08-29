package com.asaas.service.internaluser

import com.asaas.domain.user.User
import com.asaas.internaluser.handler.CallCenterInternalUserPermissionsHandler
import com.asaas.internaluser.handler.InternalUserPermissionsHandler
import com.asaas.internaluser.handler.RegulatoryInternalUserPermissionHandler
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.RoleAuthority
import grails.transaction.Transactional

@Transactional
class InternalUserPermissionBinderService {

    public void save(User user, List<RoleAuthority> userUpdatedAuthorityList, List<AdminUserPermissionName> adminUserPermissionNameList, User currentUser) {
        List<RoleAuthority> currentRoleAuthorityList = user.listRoleAuthority()

        InternalUserPermissionsHandler firstHandler = InternalUserPermissionsHandler.link(
            new CallCenterInternalUserPermissionsHandler(),
            new RegulatoryInternalUserPermissionHandler()
        )
        firstHandler.saveHandleRequest(user, currentRoleAuthorityList, userUpdatedAuthorityList, adminUserPermissionNameList, currentUser)
    }

    public void deleteUserIfNecessary(User user, User currentUser) {
        List<RoleAuthority> currentRoleAuthorityList = user.listRoleAuthority()

        InternalUserPermissionsHandler firstHandler = InternalUserPermissionsHandler.link(
            new CallCenterInternalUserPermissionsHandler(),
            new RegulatoryInternalUserPermissionHandler()
        )
        firstHandler.deleteHandleRequest(user, currentRoleAuthorityList, currentUser)
    }
}
