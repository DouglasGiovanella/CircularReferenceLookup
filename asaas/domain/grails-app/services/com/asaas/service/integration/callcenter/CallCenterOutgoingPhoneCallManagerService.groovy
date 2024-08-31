package com.asaas.service.integration.callcenter

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.outgoingphonecall.adapter.OutgoingPhoneCallAdapter
import com.asaas.integration.callcenter.outgoingphonecall.dto.SaveOutgoingPhoneCallRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.LoggerUtils

import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class CallCenterOutgoingPhoneCallManagerService {

    def grailsApplication

    public void save(OutgoingPhoneCallAdapter saveAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        SaveOutgoingPhoneCallRequestDTO saveOutgoingPhoneCallRequestDTO = new SaveOutgoingPhoneCallRequestDTO(saveAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCall/save", saveOutgoingPhoneCallRequestDTO)

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterOutgoingPhoneCallManagerService.save >> Falha ao salvar ligação de saída no CallCenter. StatusCode: [${callCenterManager.statusCode}], ErrorMessage: [${callCenterManager.getErrorMessage()}], DTO: [${LoggerUtils.sanitizeParams(saveOutgoingPhoneCallRequestDTO.properties)}]")

            throw new RuntimeException("Nao foi possivel salvar a ligação de saída no CallCenter: ${callCenterManager.getErrorMessage()}")
        }
    }

    public Boolean cancelDebtRecovery(Long asaasCustomerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return true

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCall/cancelDebtRecovery", [asaasCustomerId: asaasCustomerId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallManagerService.cancelDebtRecovery >> Falha ao cancelar ligação de saída no CallCenter. StatusCode: [${callCenterManager.statusCode}], [asaasCustomerId: ${asaasCustomerId}]")
            return false
        }

        return true
    }

    public void saveAsyncCancellation(Long asaasCustomerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCall/saveAsyncCancellation", [asaasCustomerId: asaasCustomerId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallManagerService.saveAsyncCancellation >> Falha ao salvar ação assíncrona de cancelamento de ligações de saída no CallCenter. StatusCode: [${callCenterManager.statusCode}]")
        }
    }

    public void delete(Long callCenterPhoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCall/delete", [phoneCallId: callCenterPhoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallManagerService.delete >> Falha ao remover ligação de saída no CallCenter. StatusCode: [${callCenterManager.statusCode}], [callCenterPhoneCallId: ${callCenterPhoneCallId}]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }
}
