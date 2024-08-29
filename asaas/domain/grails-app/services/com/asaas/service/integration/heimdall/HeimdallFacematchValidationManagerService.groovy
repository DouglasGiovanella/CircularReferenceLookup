package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.facematchvalidation.adapter.FacematchValidationAdapter
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.facematchValidation.HeimdallGetFacematchValidationRequestDTO
import com.asaas.integration.heimdall.dto.facematchValidation.HeimdallGetFacematchValidationResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class HeimdallFacematchValidationManagerService {

    public FacematchValidationAdapter getFacematchValidation(Long facematchCriticalActionId, Long requesterId) {
        if (!AsaasEnvironment.isProduction()) {
            HeimdallGetFacematchValidationResponseDTO heimdallGetFacematchValidationResponseDto = new MockJsonUtils("heimdall/FacematchValidationManagerService/getFacematchValidation.json").buildMock(HeimdallGetFacematchValidationResponseDTO)
            return new FacematchValidationAdapter(heimdallGetFacematchValidationResponseDto)
        }

        HeimdallGetFacematchValidationRequestDTO heimdallGetFacematchValidationRequestDTO = new HeimdallGetFacematchValidationRequestDTO(facematchCriticalActionId, requesterId, false)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post("/facematchValidations/get", heimdallGetFacematchValidationRequestDTO)

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("HeimdallFacematchValidationManagerService.getFacematchValidation >> Heimdall retornou um status diferente de sucesso ao buscar a facematchValidation. StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            throw new BusinessException("Não obtivemos sucesso na busca das informações de facematch. Por favor, tente novamente em alguns minutos.")
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        HeimdallGetFacematchValidationResponseDTO facematchValidationResponseDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, HeimdallGetFacematchValidationResponseDTO)

        return new FacematchValidationAdapter(facematchValidationResponseDTO)
    }

    public String getFacematchValidationUrl(Long facematchCriticalActionId, Long requesterId, Boolean isMobile) {
        HeimdallGetFacematchValidationRequestDTO heimdallGetFacematchValidationRequestDTO = new HeimdallGetFacematchValidationRequestDTO(facematchCriticalActionId, requesterId, isMobile)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post("/facematchValidations/getValidationUrl", heimdallGetFacematchValidationRequestDTO)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isBadRequest()) {
                AsaasLogger.warn("HeimdallFacematchValidationManagerService.getFacematchValidationUrl >> Heimdall retornou um status diferente de sucesso ao buscar URL da facematchValidation. FacematchCriticalActionId: [${facematchCriticalActionId}], ResponseBody: [${heimdallManager.responseBody}]")
                return null
            }

            AsaasLogger.error("HeimdallFacematchValidationManagerService.getFacematchValidationUrl >> Heimdall retornou um status diferente de sucesso ao buscar URL da facematchValidation. FacematchCriticalActionId: [${facematchCriticalActionId}], StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
            return null
        }

        return heimdallManager.responseBody.validationUrl
    }

    public void updateConfigProperty(String property, String value) {
        Map params = [property: property, value: value]

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.post("/facematchValidationConfigs/updateProperty", params)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            }

            throw new RuntimeException(heimdallManager.responseBody.message)
        }
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer timeout = 10000

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(timeout)

        return heimdallManager
    }
}
