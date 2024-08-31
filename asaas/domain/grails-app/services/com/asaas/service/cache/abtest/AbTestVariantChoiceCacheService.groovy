package com.asaas.service.cache.abtest

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.domain.abtest.AbTest
import com.asaas.domain.abtest.AbTestVariantChoice
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class AbTestVariantChoiceCacheService {

    def grailsCacheManager

    @Cacheable(value = "AbTestVariantChoice:getValueByNameAndDomainInstanceAndPlatform", key = "(#abTestName + ':' + #domainInstance.class.simpleName + ':' + #domainInstance.id + ':' + #abTestPlatform).toString()")
    public String getValueByNameAndDomainInstanceAndPlatform(String abTestName, Object domainInstance, AbTestPlatform abTestPlatform) {
        return AbTestVariantChoice.query([abTestName: abTestName, domainInstance: domainInstance, returnVariantValueOnly: true, abTestPlatform: abTestPlatform]).get()
    }

    @Cacheable(value = "AbTestVariantChoice:getValueByNameAndDistinctIdAndPlatform", key = "(#abTestName + ':' + #distinctId + ':' + #abTestPlatform).toString()")
    public String getValueByNameAndDistinctIdAndPlatform(String abTestName, String distinctId, AbTestPlatform abTestPlatform) {
        return AbTestVariantChoice.query([abTestName: abTestName, distinctId: distinctId, returnVariantValueOnly: true, abTestPlatform: abTestPlatform]).get()
    }

    public void evict(AbTestVariantChoice abTestVariantChoice) {
        final AbTest abTest = abTestVariantChoice.abTestVariant.abTest

        grailsCacheManager.getCache("AbTestVariantChoice:getValueByNameAndDomainInstanceAndPlatform").evict("${abTest.name}:${abTestVariantChoice.object}:${abTestVariantChoice.objectId}:${abTest.platform}")
        grailsCacheManager.getCache("AbTestVariantChoice:getValueByNameAndDistinctIdAndPlatform").evict("${abTest.name}:${abTestVariantChoice.distinctId}:${abTest.platform}")
    }

    public void put(AbTestVariantChoice abTestVariantChoice) {
        final AbTest abTest = abTestVariantChoice.abTestVariant.abTest
        final String variantValue = abTestVariantChoice.abTestVariant.value

        if (abTestVariantChoice.distinctId) {
            grailsCacheManager.getCache("AbTestVariantChoice:getValueByNameAndDistinctIdAndPlatform").put("${abTest.name}:${abTestVariantChoice.distinctId}:${abTest.platform}", variantValue)
        }

        if (abTestVariantChoice.objectId) {
            grailsCacheManager.getCache("AbTestVariantChoice:getValueByNameAndDomainInstanceAndPlatform").put("${abTest.name}:${abTestVariantChoice.object}:${abTestVariantChoice.objectId}:${abTest.platform}", variantValue)
        }
    }
}
