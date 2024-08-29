package com.asaas.service.featureflag

import com.asaas.domain.featureflag.FeatureFlag
import com.asaas.featureflag.FeatureFlagName
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class FeatureFlagCacheService {

    static final String IS_ENABLED_CACHE_KEY = "FeatureFlagCache:isEnabled"

    @Cacheable(value = FeatureFlagCacheService.IS_ENABLED_CACHE_KEY)
    public Boolean isEnabled(FeatureFlagName name) {
        Boolean featureFlagValue = FeatureFlag.query([column: "enabled", name: name]).get()
        if (!featureFlagValue) return false

        return featureFlagValue
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = FeatureFlagCacheService.IS_ENABLED_CACHE_KEY)
    public void evictIsEnabled(FeatureFlagName name) { }
}
