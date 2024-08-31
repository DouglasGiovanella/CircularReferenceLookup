package com.asaas.service.internaluser

import com.asaas.domain.user.Role
import com.asaas.domain.user.User
import com.asaas.domain.user.UserRole
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.DeveloperActionPermissionName
import com.asaas.userpermission.RoleAuthority

import grails.transaction.Transactional

@Transactional
class InternalUserPermissionsService {

    def adminUserPermissionService
    def developerActionPermissionService
    def internalUserPermissionBinderService
    def loginSessionService
    def userWebAuthenticationService

    public void setPermissions(User user, List<RoleAuthority> roleAuthorityList, List<AdminUserPermissionName> adminUserPermissionNameList, List<DeveloperActionPermissionName> developerActionPermissionNameList, User currentUser) {
        if (!UserUtils.hasAsaasEmail(user.username)) throw new BusinessException("Só é permitido definir as permissões para usuários internos.")

        internalUserPermissionBinderService.save(user, roleAuthorityList, adminUserPermissionNameList, currentUser)

        roleAuthorityList.add(RoleAuthority.ROLE_SYSADMIN)
        setRoles(user, roleAuthorityList)
        adminUserPermissionService.saveList(user, adminUserPermissionNameList)
        developerActionPermissionService.saveList(user, developerActionPermissionNameList)
    }

    public void updatePermissions(User user, List<RoleAuthority> roleAuthorityList, List<AdminUserPermissionName> adminUserPermissionNameList, List<DeveloperActionPermissionName> developerActionPermissionNameList, Long currentUserId) {
        if (!UserUtils.isAsaasTeam(user.username)) throw new BusinessException("Só é permitido atualizar as permissões para usuários internos.")
        User currentUser = User.read(currentUserId)

        internalUserPermissionBinderService.save(user, roleAuthorityList, adminUserPermissionNameList, currentUser)

        updateRoles(user, roleAuthorityList, currentUserId)
        adminUserPermissionService.update(user, adminUserPermissionNameList)
        developerActionPermissionService.update(user, developerActionPermissionNameList)
    }

    public void deleteAdminPermissionsIfNecessary(User user, User currentUser) {
        deleteAdminRoles(user, currentUser)
        adminUserPermissionService.deleteAll(user)
        developerActionPermissionService.deleteAll(user)
    }

    private void deleteAdminRoles(User user, User currentUser) {
        List<UserRole> userRoleList = UserRole.query([user: user]).list()

        internalUserPermissionBinderService.deleteUserIfNecessary(user, currentUser)

        for (UserRole userRole : userRoleList) {
            RoleAuthority roleAuthority = RoleAuthority.convert(userRole.role.authority)
            if (!RoleAuthority.listUserRoles().contains(roleAuthority)) userRole.delete(flush: true)
        }
    }

    private void setRoles(User user, List<RoleAuthority> roleList) {
        for (RoleAuthority role in roleList) {
            UserRole.create(user, Role.query(['authority': role.toString()]).get(), true)
        }
    }

    private void updateRoles(User user, List<RoleAuthority> roleAuthorityList, Long currentUserId) {
        List<RoleAuthority> newRoleList = roleAuthorityList - user.listRoleAuthority()
        setRoles(user, newRoleList)

        final List<RoleAuthority> standardRoles = [RoleAuthority.ROLE_ADMIN, RoleAuthority.ROLE_USER, RoleAuthority.ROLE_SYSADMIN]
        List<RoleAuthority> roleListToDelete = user.listRoleAuthority() - (roleAuthorityList + standardRoles)
        deleteRole(user, roleListToDelete)

        Boolean updatedRoles = newRoleList.size() > 0 || roleListToDelete.size() > 0
        if (updatedRoles) {
            if (currentUserId == user.id) {
                userWebAuthenticationService.reauthenticate(user.id)
            } else {
                loginSessionService.invalidateUserLoginSessions(user.id)
            }
        }
    }

    private void deleteRole(User user, List<RoleAuthority> roleAuthorityList) {
        for (RoleAuthority roleAuthority in roleAuthorityList) {
            Role role = Role.query([authority: roleAuthority.toString()]).get()
            UserRole userRole = UserRole.query([user: user, role: role]).get()
            userRole.delete(flush: true)
        }
    }
}
