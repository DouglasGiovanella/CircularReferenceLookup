package com.asaas.service.developeractionpermission

import com.asaas.domain.user.User
import com.asaas.domain.userpermission.DeveloperActionPermission
import com.asaas.exception.BusinessException
import com.asaas.userpermission.DeveloperActionPermissionName
import grails.transaction.Transactional

@Transactional
class DeveloperActionPermissionService {

    def userPermissionLogService

    public void saveList(User user, List<DeveloperActionPermissionName> permissionNameList) {
        if (!permissionNameList) return
        if (!user.belongsToDevelopmentTeam()) throw new BusinessException("Só é permitido definir permissões de desenvolvedor para usuários com perfil de desenvolvedor.")

        for (DeveloperActionPermissionName permissionName in permissionNameList) {
            save(user, permissionName)
        }
    }

    public void deleteList(User user, List<DeveloperActionPermissionName> permissionNameList) {
        for (DeveloperActionPermissionName permissionName in permissionNameList) {
            delete(user, permissionName)
        }
    }

    public void deleteAll(User user) {
        List<DeveloperActionPermission> developerActionPermissionList = list(user)
        deleteList(user, developerActionPermissionList)
    }

    public void update(User user, List<DeveloperActionPermissionName> permissionNameList) {
        List<DeveloperActionPermissionName> newPermissionNameList = permissionNameList - list(user)
        saveList(user, newPermissionNameList)

        List<DeveloperActionPermissionName> permissionNameListToDelete = list(user) - permissionNameList
        deleteList(user, permissionNameListToDelete)
    }

    public List<DeveloperActionPermission> list(User user) {
        return DeveloperActionPermission.query([column: "permission", user: user]).list()
    }

    private void save(User user, DeveloperActionPermissionName permissionName) {
        DeveloperActionPermission developerActionPermission = new DeveloperActionPermission()
        developerActionPermission.user = user
        developerActionPermission.permission = permissionName
        developerActionPermission.save(failOnError: true)

        userPermissionLogService.saveInsert(user.id, developerActionPermission.class.simpleName, permissionName.toString())
    }

    private void delete(User user, DeveloperActionPermissionName permissionName) {
        DeveloperActionPermission developerActionPermission = DeveloperActionPermission.query([user: user, permission: permissionName]).get()
        developerActionPermission.delete(flush: true)

        userPermissionLogService.saveDelete(user.id, developerActionPermission.class.simpleName, permissionName.toString())
    }
}
