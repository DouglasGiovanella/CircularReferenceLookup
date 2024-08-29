package com.asaas.service.integration

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.dataenhancement.get.common.analysis.DataEnhancementAnalysisDTO
import com.asaas.integration.heimdall.dto.dataenhancement.get.legal.LegalDataEnhancementDTO
import com.asaas.integration.heimdall.dto.dataenhancement.get.natural.NaturalDataEnhancementDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class DataEnhancementManagerService {

    public Map save(String cpfCnpj, Date birthDate) {
        if (!AsaasEnvironment.isProduction()) return [:]

        Map params = [:]
        String path = ""

        if (CpfCnpjUtils.isCnpj(cpfCnpj)) {
            path = "/legalDataEnhancement/${cpfCnpj}"
            params.cnpj = cpfCnpj
        } else {
            path = "/naturalDataEnhancement/${cpfCnpj}"
            params.cpf = cpfCnpj
            if (birthDate) params.birthDate = CustomDateUtils.fromDate(birthDate)
        }

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post(path, params)

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("Ocorreu um problema no servidor do Heimdall e não foi possível salvar o cpf/cnpj [${cpfCnpj}]")
        }

        return heimdallManager.responseBody
    }

    public NaturalDataEnhancementDTO findNaturalDataEnhancement(String cpf) {
        if (!CpfCnpjUtils.isCpf(cpf)) throw new BusinessException("CPF inválido [${cpf}]")

        return get("/naturalDataEnhancement/${cpf}", NaturalDataEnhancementDTO)
    }

    public LegalDataEnhancementDTO findLegalDataEnhancement(String cnpj) {
        if (!CpfCnpjUtils.isCnpj(cnpj)) throw new BusinessException("CNPJ inválido [${cnpj}]")

        return get("/legalDataEnhancement/${cnpj}", LegalDataEnhancementDTO)
    }

    public DataEnhancementAnalysisDTO findAnalysis(String cpfCnpj) {
        String path = CpfCnpjUtils.isCnpj(cpfCnpj) ? 'legalDataEnhancement' : 'naturalDataEnhancement'

        return get("/${path}/showAnalysis/${cpfCnpj}", DataEnhancementAnalysisDTO)
    }

    public void enableToReprocess(Long id, String type) {
        Map params = [id: id]

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/${type}/enableToReprocess", params)

        if (!heimdallManager.isSuccessful()) {
            throw new BusinessException((heimdallManager.responseBody as JSON).toString())
        }
    }

    private get(String path, returningDtoClass) {
        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.info("DataEnhancementManagerService.get >> StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]. Path [${path}]")
                return null
            }

            AsaasLogger.error("DataEnhancementManagerService.get >> Heimdall retornou um status diferente de sucesso ao buscar a análise do cliente. StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]. Path [${path}]", new Throwable())
            return null
        }

        String responseBody = (heimdallManager.responseBody as JSON).toString()
        Object responseDTO = GsonBuilderUtils.buildClassFromJson(responseBody, returningDtoClass)

        return responseDTO
    }
}
