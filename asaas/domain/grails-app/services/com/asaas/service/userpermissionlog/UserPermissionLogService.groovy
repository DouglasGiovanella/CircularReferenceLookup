package com.asaas.service.userpermissionlog

import com.asaas.domain.user.UserPermissionLog
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class UserPermissionLogService {

    public void saveInsert(Long userId, String className, String permissionIdentifier) {
        save(userId, className, permissionIdentifier, "INSERT")
    }

    public void saveDelete(Long userId, String className, String permissionIdentifier) {
        save(userId, className, permissionIdentifier, "DELETE")
    }

    private void save(Long userId, String className, String permissionIdentifier, String eventName) {
        UserPermissionLog userPermissionLog = new UserPermissionLog()
        userPermissionLog.dateCreated = new Date()
        userPermissionLog.actor = UserUtils.getActor()
        userPermissionLog.userId = userId
        userPermissionLog.className = className
        userPermissionLog.permissionIdentifier = permissionIdentifier
        userPermissionLog.eventName = eventName
        userPermissionLog.save(failOnError: true)
    }
}
