package com.asaas.service.adminuserpermission

import com.asaas.domain.user.User
import com.asaas.domain.userpermission.AdminUserPermission
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.RoleAuthority

import grails.transaction.Transactional

@Transactional
class AdminUserPermissionService {

    def adminUserPermissionCacheService
    def asaasMoneyManagerService
    def oceanManagerService
    def userPermissionLogService
    def sageManagerService

    public void saveList(User user, List<AdminUserPermissionName> permissionNameList) {
        for (AdminUserPermissionName permissionName in permissionNameList) {
            save(user, permissionName)
        }

        Boolean addedPermissions = permissionNameList.size() > 0
        if (addedPermissions) adminUserPermissionCacheService.evictByUserId(user.id)
    }

    public void deleteList(User user, List<AdminUserPermissionName> permissionNameList) {
        for (AdminUserPermissionName permissionName in permissionNameList) {
            delete(user, permissionName)
        }

        Boolean removedPermissions = permissionNameList.size() > 0
        if (removedPermissions) adminUserPermissionCacheService.evictByUserId(user.id)
    }

    public void deleteAll(User user) {
        List<AdminUserPermissionName> adminUserPermissionList = list(user)
        deleteList(user, adminUserPermissionList)
    }

    public void update(User user, List<AdminUserPermissionName> permissionNameList) {
        List<AdminUserPermissionName> newPermissionNameList = permissionNameList - list(user)
        saveList(user, newPermissionNameList)

        List<AdminUserPermissionName> permissionNameListToDelete = list(user) - permissionNameList
        deleteList(user, permissionNameListToDelete)
    }

    public List<AdminUserPermission> list(User user) {
        return AdminUserPermission.query([column: "permission", user: user]).list()
    }

    public List<AdminUserPermissionName> listSelectOptions(List<RoleAuthority> authorityList) {
        List<AdminUserPermissionName> permissionList = AdminUserPermissionName.listPermissionsWithoutRoleAssociated()

        for (RoleAuthority authority in authorityList) {
            permissionList.addAll(AdminUserPermissionName.listPermissionsWithRoleAssociated(authority))
        }

        return permissionList
    }

    private void save(User user, AdminUserPermissionName permissionName) {
        AdminUserPermission adminUserPermission = new AdminUserPermission()
        adminUserPermission.user = user
        adminUserPermission.permission = permissionName
        adminUserPermission.save(failOnError: true)

        userPermissionLogService.saveInsert(user.id, adminUserPermission.class.simpleName, permissionName.toString())

        if (permissionName.isAccessOcean()) oceanManagerService.enableUser(user.id, user.username)

        if (permissionName.isAccessMoney()) asaasMoneyManagerService.enableUser(user.id, user.username)

        if (permissionName.isAccessSage()) sageManagerService.enableUser(user.id, user.username)
    }

    private void delete(User user, AdminUserPermissionName permissionName) {
        AdminUserPermission adminUserPermission = AdminUserPermission.query([user: user, permission: permissionName]).get()
        adminUserPermission.delete(flush: true)

        userPermissionLogService.saveDelete(user.id, adminUserPermission.class.simpleName, permissionName.toString())

        if (permissionName.isAccessOcean()) oceanManagerService.disableUser(user.id)

        if (permissionName.isAccessMoney()) asaasMoneyManagerService.disableUser(user.id)

        if (permissionName.isAccessSage()) sageManagerService.disableUser(user.id)
    }
}
