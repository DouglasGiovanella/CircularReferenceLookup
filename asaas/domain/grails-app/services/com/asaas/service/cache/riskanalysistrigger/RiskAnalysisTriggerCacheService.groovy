package com.asaas.service.cache.riskanalysistrigger

import com.asaas.cache.riskanalysistrigger.RiskAnalysisTriggerCacheVO
import com.asaas.domain.riskAnalysis.RiskAnalysisTrigger
import com.asaas.riskAnalysis.RiskAnalysisReason
import grails.plugin.cache.Cacheable
import grails.plugin.cache.CacheEvict
import grails.transaction.Transactional

@Transactional
class RiskAnalysisTriggerCacheService {

    @Cacheable(value = "RiskAnalysisTrigger:getInstance", key = "#riskAnalysisReason")
    public RiskAnalysisTriggerCacheVO getInstance(RiskAnalysisReason riskAnalysisReason) {
        RiskAnalysisTrigger riskAnalysisTrigger = RiskAnalysisTrigger.query([riskAnalysisReason: riskAnalysisReason]).get()

        return RiskAnalysisTriggerCacheVO.build(riskAnalysisTrigger)
    }

    @SuppressWarnings("UnusedMethodParameter")
    @CacheEvict(value = "RiskAnalysisTrigger:getInstance", key="#riskAnalysisReason")
    public void evictGetInstance(RiskAnalysisReason riskAnalysisReason) {
        return
    }
}
