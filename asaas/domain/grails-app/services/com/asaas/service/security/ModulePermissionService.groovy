package com.asaas.service.security


import com.asaas.domain.user.Role
import com.asaas.domain.user.User
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional

@Transactional
class ModulePermissionService {

	def grailsApplication

    def springSecurityService

	public Boolean allowed(User user, String module) {
        Long currentUserId = springSecurityService.currentUserId
        if (user.id == currentUserId) return allowed(module)

        for (Role role in user.getAuthorities()) {
            if (grailsApplication.config.asaas.module.permission."${module}".contains(role.authority)) return true
        }

        return false
	}

    public Boolean allowed(String module) {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.module.permission."${module}".join(","))
    }
}
