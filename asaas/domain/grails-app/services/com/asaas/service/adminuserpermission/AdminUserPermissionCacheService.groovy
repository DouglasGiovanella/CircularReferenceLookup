package com.asaas.service.adminuserpermission

import com.asaas.domain.userpermission.AdminUserPermission
import com.asaas.log.AsaasLogger
import com.asaas.userpermission.AdminUserPermissionName
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class AdminUserPermissionCacheService {

    @Cacheable(value = "AdminUserPermissionCache:byUserId", key = "#userId")
    public AdminUserPermissionName[] listByUserId(Long userId) {
        try {
            return AdminUserPermission.query([column: "permission", userId: userId]).list()
        } catch (IllegalArgumentException illegalArgumentException) {
            String depreciatedPermission = illegalArgumentException.getMessage().split(":")[-1].trim()
            AsaasLogger.error("AdminUserPermissionCacheService.listByUserId >> O usuário [${userId}] possui a permissao [${depreciatedPermission}] que foi removida", illegalArgumentException)
            return AdminUserPermission.query([column: "permission", permissionList: AdminUserPermissionName.values(), userId: userId]).list()
        }
    }

    @CacheEvict(value = "AdminUserPermissionCache:byUserId", key = "#userId")
    @SuppressWarnings("UnusedMethodParameter")
    public void evictByUserId(Long userId) {
        return // Apenas remove a chave do Redis
    }
}
