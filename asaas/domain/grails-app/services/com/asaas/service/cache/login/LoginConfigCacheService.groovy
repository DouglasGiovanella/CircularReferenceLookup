package com.asaas.service.cache.login

import com.asaas.cache.login.LoginConfigCacheVO
import com.asaas.domain.login.LoginConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class LoginConfigCacheService {

    @Cacheable(value = "LoginConfig:getThirdPartyLoginValidationConfig", key = "'thirdPartyLoginValidationConfig'")
    public LoginConfigCacheVO getThirdPartyLoginValidationConfig() {
        LoginConfig loginConfig = LoginConfig.getInstance()
        return LoginConfigCacheVO.buildThirdPartyLoginValidationConfig(loginConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "LoginConfig:getThirdPartyLoginValidationConfig", key = "'thirdPartyLoginValidationConfig'")
    public void evictThirdPartyLoginValidationConfig() {
        return
    }
}
