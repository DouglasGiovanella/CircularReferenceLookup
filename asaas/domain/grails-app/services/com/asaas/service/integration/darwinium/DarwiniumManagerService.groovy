package com.asaas.service.integration.darwinium

import com.asaas.integration.darwinium.DarwiniumManager
import com.asaas.integration.darwinium.DarwiniumSslContextProxy
import com.asaas.integration.darwinium.adapter.ProfileAnalysisResultAdapter
import com.asaas.integration.darwinium.dto.profileanalysisresult.DarwiniumGetProfileAnalysisResultResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class DarwiniumManagerService {

    private static final API_SERVER_URL_PATH = "/event/login/apistep"

    public ProfileAnalysisResultAdapter buildProfileResult(String identifier) {
        Map requestHeader = ["from_identifier": identifier]
        DarwiniumManager darwiniumManager = new DarwiniumManager(DarwiniumSslContextProxy.SSL_CONTEXT_INSTANCE)
        darwiniumManager.post(API_SERVER_URL_PATH, [:], requestHeader)

        if (!darwiniumManager.isSuccessful()) {
            AsaasLogger.error("DarwiniumManagerService.buildProfileResult >> Erro ao chamar API Darwinium. StatusCode: [${darwiniumManager.statusCode}], ResponseBody: [${darwiniumManager.responseBody}]")
            return null
        }

        DarwiniumGetProfileAnalysisResultResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((darwiniumManager.responseBody as JSON).toString(), DarwiniumGetProfileAnalysisResultResponseDTO)
        return new ProfileAnalysisResultAdapter(responseDTO)
    }
}
