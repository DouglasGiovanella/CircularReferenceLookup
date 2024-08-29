package com.asaas.service.integration.callcenter

import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.file.adapter.CallCenterFileAdapter
import com.asaas.integration.callcenter.file.dto.CallCenterFileResponseDTO
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class CallCenterFileManagerService {

    def grailsApplication

    public CallCenterFileAdapter download(String publicId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/callCenterFile/download", [publicId: publicId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterFileManagerService.download >> Falha ao fazer download de arquivo. PublicId: [${publicId}]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }

        CallCenterFileResponseDTO callCenterFileResponseDTO = new CallCenterFileResponseDTO(callCenterManager.responseBody.asaasFindCallCenterFileResponseDTO)

        return new CallCenterFileAdapter(callCenterFileResponseDTO)
    }
}
