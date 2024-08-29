package com.asaas.service.cache.api

import com.asaas.cache.api.ApiConfigCacheVO
import com.asaas.domain.api.ApiConfig
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class ApiConfigCacheService {

    def grailsCacheManager

    @Cacheable(value = "ApiConfigCache:byId")
    public ApiConfigCacheVO getById(Long id) {
        if (!id) return null

        ApiConfig apiConfig = ApiConfig.query([id: id]).get()
        if (!apiConfig) return null

        return ApiConfigCacheVO.build(apiConfig)
    }

    @Cacheable(value = "ApiConfigCache:byCustomerId")
    public ApiConfigCacheVO getByCustomer(Long customerId) {
        if (!customerId) return null

        ApiConfig apiConfig = ApiConfig.query([providerId: customerId]).get()
        if (!apiConfig) return null

        return ApiConfigCacheVO.build(apiConfig)
    }

    @Cacheable(value = "ApiConfigCache:byEncryptedAccessToken")
    public ApiConfigCacheVO getByEncryptedAccessToken(String encryptedAccessToken) {
        if (!encryptedAccessToken) return null

        ApiConfig apiConfig = ApiConfig.query([accessToken: encryptedAccessToken]).get()
        if (!apiConfig) return null

        return ApiConfigCacheVO.build(apiConfig)
    }

    public void evict(ApiConfig apiConfig) {
        grailsCacheManager.getCache("ApiConfigCache:byId").evict(apiConfig.id)
        grailsCacheManager.getCache("ApiConfigCache:byCustomerId").evict(apiConfig.providerId)
        grailsCacheManager.getCache("ApiConfigCache:byEncryptedAccessToken").evict(apiConfig.accessToken)
    }
}
