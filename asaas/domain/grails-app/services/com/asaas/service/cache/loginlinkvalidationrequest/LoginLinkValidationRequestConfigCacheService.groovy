package com.asaas.service.cache.loginlinkvalidationrequest

import com.asaas.cache.loginlinkvalidationrequest.LoginLinkValidationRequestConfigCacheVO
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequestConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class LoginLinkValidationRequestConfigCacheService {

    @Cacheable(value = "LoginLinkValidationRequestConfig:getInstance", key = "'instance'")
    public LoginLinkValidationRequestConfigCacheVO getInstance() {
        LoginLinkValidationRequestConfig loginLinkValidationRequestConfig = LoginLinkValidationRequestConfig.getInstance()
        return LoginLinkValidationRequestConfigCacheVO.build(loginLinkValidationRequestConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "LoginLinkValidationRequestConfig:getInstance", key = "'instance'")
    public void evict() {
        return
    }
}
