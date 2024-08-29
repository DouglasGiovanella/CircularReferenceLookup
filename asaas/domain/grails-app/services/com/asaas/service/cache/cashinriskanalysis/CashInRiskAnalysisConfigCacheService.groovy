package com.asaas.service.cache.cashinriskanalysis

import com.asaas.cache.cashinriskanalysis.CashInRiskAnalysisConfigCacheVO
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisConfig
import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class CashInRiskAnalysisConfigCacheService {

    @Cacheable(value = "CashInRiskAnalysisConfig:getInstance", key="'instance'")
    public CashInRiskAnalysisConfigCacheVO getInstance() {
        CashInRiskAnalysisConfig cashInRiskAnalysisConfig = CashInRiskAnalysisConfig.getInstance()
        return CashInRiskAnalysisConfigCacheVO.build(cashInRiskAnalysisConfig)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "CashInRiskAnalysisConfig:getInstance", key="'instance'")
    public void evict() {
        return
    }
}
