package com.asaas.service.cache.checkoutriskanalysis

import com.asaas.cache.checkoutriskanalysis.CheckoutRiskAnalysisConfigCacheVO
import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReason
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CheckoutRiskAnalysisConfigCacheService {

    @Cacheable(value = "CheckoutRiskAnalysisConfig:getInstance", key = "#checkoutRiskAnalysisReason")
    public CheckoutRiskAnalysisConfigCacheVO getInstance(CheckoutRiskAnalysisReason checkoutRiskAnalysisReason) {
        CheckoutRiskAnalysisConfig checkoutRiskAnalysisConfig = CheckoutRiskAnalysisConfig.query([checkoutRiskAnalysisReason: checkoutRiskAnalysisReason, readOnly: true]).get()

        return CheckoutRiskAnalysisConfigCacheVO.build(checkoutRiskAnalysisConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "CheckoutRiskAnalysisConfig:getInstance", key = "'instance'")
    public void evict() {
        return
    }
}
