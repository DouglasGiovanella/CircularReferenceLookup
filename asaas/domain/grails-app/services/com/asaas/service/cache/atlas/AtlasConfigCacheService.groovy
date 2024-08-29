package com.asaas.service.cache.atlas

import com.asaas.cache.atlas.AtlasConfigCacheVO
import com.asaas.domain.atlas.AtlasConfig
import grails.plugin.cache.Cacheable
import grails.plugin.cache.CacheEvict
import grails.transaction.Transactional

@Transactional
class AtlasConfigCacheService {

    @Cacheable(value = "AtlasConfig:byCurrentRelease", key = "'releaseId'")
    public AtlasConfigCacheVO getByCurrentRelease() {
        AtlasConfig atlasConfig = AtlasConfig.query([isCurrentRelease: true]).get()
        if (!atlasConfig) return null

        return AtlasConfigCacheVO.build(atlasConfig)
    }

    @CacheEvict(value = "AtlasConfig:byCurrentRelease", key = "'releaseId'")
    @SuppressWarnings("UnusedMethodParameter")
    public void evict() {
        return // Apenas remove a chave do Redis
    }
}
