package com.asaas.service.integration.sauron

import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.sauron.adapter.riskanalysis.ExternalRiskAnalysisTriggerAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.riskanalysis.children.SauronRiskAnalysisSourceDTO
import com.asaas.log.AsaasLogger
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class RiskAnalysisManagerService {

    public ExternalRiskAnalysisTriggerAdapter findByRiskAnalysisReason(RiskAnalysisReason riskAnalysisReason) {
        SauronManager sauronManager = buildSauronManager()
        sauronManager.post("/riskAnalysisSource/findByRiskAnalysisReason", [riskAnalysisReason: riskAnalysisReason.toString()])

        if (!sauronManager.isSuccessful()) {
            AsaasLogger.error("RiskAnalysisManagerService.findByRiskAnalysisReason >> Sauron retornou um status diferente de sucesso. StatusCode: [${sauronManager.statusCode}], ResponseBody: [${sauronManager.responseBody}]")
            throw new BusinessException("Ocorreu uma falha na busca das informações do Bait.")
        }

        String responseBodyJson = (sauronManager.responseBody as JSON).toString()
        SauronRiskAnalysisSourceDTO responseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, SauronRiskAnalysisSourceDTO)
        if (!responseDTO) throw new ResourceNotFoundException("Bait não encontrado. RiskAnalysisReason [${riskAnalysisReason}].")

        return new ExternalRiskAnalysisTriggerAdapter(responseDTO)
    }

    public ExternalRiskAnalysisTriggerAdapter update(ExternalRiskAnalysisTriggerAdapter externalRiskAnalysisTriggerAdapter) {
        SauronManager sauronManager = buildSauronManager()
        sauronManager.put("/riskAnalysisSource/${externalRiskAnalysisTriggerAdapter.id}", externalRiskAnalysisTriggerAdapter.properties)

        if (!sauronManager.isSuccessful()) {
            AsaasLogger.error("RiskAnalysisManagerService.update >> Sauron retornou um status diferente de sucesso. StatusCode: [${sauronManager.statusCode}], ResponseBody: [${sauronManager.responseBody}]")
            throw new BusinessException("Ocorreu uma falha na atualização das informações do Bait.")
        }

        String responseBodyJson = (sauronManager.responseBody as JSON).toString()
        SauronRiskAnalysisSourceDTO responseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, SauronRiskAnalysisSourceDTO)
        if (!responseDTO) throw new ResourceNotFoundException("Ocorreu uma falha na atualização das informações do Bait.")

        return new ExternalRiskAnalysisTriggerAdapter(responseDTO)
    }

    private SauronManager buildSauronManager() {
        final Integer timeout = 10000

        SauronManager sauronManager = new SauronManager()
        sauronManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        sauronManager.setTimeout(timeout)

        return sauronManager
    }
}
