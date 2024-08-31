package com.asaas.service.riskanalysis

import com.asaas.domain.riskAnalysis.RiskAnalysisTrigger
import com.asaas.integration.sauron.adapter.riskanalysis.ExternalRiskAnalysisTriggerAdapter
import com.asaas.riskAnalysis.adapter.RiskAnalysisTriggerAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RiskAnalysisTriggerService {

    def riskAnalysisManagerService
    def riskAnalysisTriggerCacheService

    public RiskAnalysisTriggerAdapter buildRiskAnalysisTriggerData(RiskAnalysisTrigger riskAnalysisTrigger) {
        if (!riskAnalysisTrigger.isExternal) return new RiskAnalysisTriggerAdapter(riskAnalysisTrigger)

        ExternalRiskAnalysisTriggerAdapter externalRiskAnalysisTriggerAdapter = riskAnalysisManagerService.findByRiskAnalysisReason(riskAnalysisTrigger.riskAnalysisReason)
        return new RiskAnalysisTriggerAdapter(riskAnalysisTrigger, externalRiskAnalysisTriggerAdapter)
    }

    public RiskAnalysisTrigger update(RiskAnalysisTrigger riskAnalysisTrigger, Map params) {
        Map parsedParams = parseUpdateParams(params)

        RiskAnalysisTrigger validatedRiskAnalysisTrigger = validateUpdate(parsedParams)
        if (validatedRiskAnalysisTrigger.hasErrors()) return validatedRiskAnalysisTrigger

        toggleExternalIfNecessary(riskAnalysisTrigger, parsedParams.enabled)
        riskAnalysisTrigger.enabled = parsedParams.enabled
        riskAnalysisTrigger.description = parsedParams.description
        riskAnalysisTrigger.score = parsedParams.score
        riskAnalysisTrigger.activationDelayInDays = parsedParams.activationDelayInDays
        riskAnalysisTrigger.save(failOnError: true)

        riskAnalysisTriggerCacheService.evictGetInstance(riskAnalysisTrigger.riskAnalysisReason)

        return riskAnalysisTrigger
    }

    public void toggleEnable(RiskAnalysisTrigger riskAnalysisTrigger, Boolean enabled) {
        toggleExternalIfNecessary(riskAnalysisTrigger, enabled)

        riskAnalysisTrigger.enabled = enabled
        riskAnalysisTrigger.save(failOnError: true)

        riskAnalysisTriggerCacheService.evictGetInstance(riskAnalysisTrigger.riskAnalysisReason)
    }

    private void toggleExternalIfNecessary(RiskAnalysisTrigger riskAnalysisTrigger, Boolean enabled) {
        if (!riskAnalysisTrigger.isExternal) return

        ExternalRiskAnalysisTriggerAdapter externalRiskAnalysisTriggerAdapter = new ExternalRiskAnalysisTriggerAdapter(riskAnalysisTrigger, enabled)
        riskAnalysisManagerService.update(externalRiskAnalysisTriggerAdapter)
    }

    private Map parseUpdateParams(Map params) {
        if (params.containsKey("enabled")) {
            params.enabled = Boolean.valueOf(params.enabled)
        }

        if (params.containsKey("score")) {
            params.score = Integer.valueOf(params.score)
        }

        if (params.containsKey("activationDelayInDays")) {
            params.activationDelayInDays = Integer.valueOf(params.activationDelayInDays)
        }

        return params
    }

    private RiskAnalysisTrigger validateUpdate(Map updateParams) {
        RiskAnalysisTrigger validatedRiskAnalysisTrigger = new RiskAnalysisTrigger()

        if (Utils.isEmptyOrNull(updateParams.description)) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "A descrição é obrigatória")
            return validatedRiskAnalysisTrigger
        }

        if (Utils.isEmptyOrNull(updateParams.score)) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "O score é obrigatório")
            return validatedRiskAnalysisTrigger
        }

        if (updateParams.score < 0) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "O score não pode ser negativo")
            return validatedRiskAnalysisTrigger
        }

        if (Utils.isEmptyOrNull(updateParams.activationDelayInDays)) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "A quantidade de dias de espera para reincidência é obrigatória")
            return validatedRiskAnalysisTrigger
        }

        if (updateParams.activationDelayInDays < 0) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "A quantidade de dias de espera para reincidência não pode ser negativa")
            return validatedRiskAnalysisTrigger
        }

        if (Utils.isEmptyOrNull(updateParams.enabled)) {
            DomainUtils.addError(validatedRiskAnalysisTrigger, "O status é obrigatório")
            return validatedRiskAnalysisTrigger
        }

        return validatedRiskAnalysisTrigger
    }
}
