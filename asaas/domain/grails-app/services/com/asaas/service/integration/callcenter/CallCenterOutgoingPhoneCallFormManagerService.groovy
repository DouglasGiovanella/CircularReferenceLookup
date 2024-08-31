package com.asaas.service.integration.callcenter

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.phonecall.adapter.FinishIncomingCallAttendanceAdapter as FinishPhoneCallAttendanceAdapter
import com.asaas.integration.callcenter.outgoingphonecall.adapter.OutgoingPhoneCallFormAdapter
import com.asaas.integration.callcenter.outgoingphonecall.dto.CallCenterFindAttendanceInfoResponseDTO
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter
import com.asaas.integration.callcenter.supportattendance.dto.CallCenterFinishAttendanceRequestDTO
import com.asaas.integration.callcenter.supportattendance.dto.CallCenterFinishAttendanceResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class CallCenterOutgoingPhoneCallFormManagerService {

    def grailsApplication

    public OutgoingPhoneCallFormAdapter findAttendanceInfo(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) throw new RuntimeException("Não é possível abrir o formulário de ligação com o testMode ativo")

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/outgoingPhoneCallForm/findAttendanceInfo", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallFormManagerService.findAttendanceInfo >> Falha ao consultar informações da ligação de saída no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: $phoneCallId]]")
            throw new RuntimeException("Falha ao consultar informações da ligação de saída")
        }

        CallCenterFindAttendanceInfoResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterFindAttendanceInfoResponseDTO)
        return new OutgoingPhoneCallFormAdapter(responseDTO)
    }

    public SupportAttendanceAdapter finishAttendance(FinishPhoneCallAttendanceAdapter finishPhoneCallAttendanceAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterFinishAttendanceRequestDTO finishOutgoingPhoneCallRequestDTO = new CallCenterFinishAttendanceRequestDTO(finishPhoneCallAttendanceAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCallForm/finishAttendance", finishOutgoingPhoneCallRequestDTO)

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterOutgoingPhoneCallFormManagerService.finishAttendance >> Falha ao finalizar ligação de saída no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [${LoggerUtils.sanitizeParams(finishPhoneCallAttendanceAdapter.properties)}]]")

            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }

        CallCenterFinishAttendanceResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterFinishAttendanceResponseDTO)
        return new SupportAttendanceAdapter(responseDTO)
    }

    public void callAgainWhenConnectionDropped(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCallForm/callAgainWhenConnectionDropped", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallFormManagerService.callAgainWhenConnectionDropped >> Erro ao ligar novamente para o cliente quando a ligação cai. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: $phoneCallId]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public void finishAttendanceWhenCloseForm(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/outgoingPhoneCallForm/finishAttendanceWhenCloseForm", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterOutgoingPhoneCallFormManagerService.finishAttendanceWhenCloseForm >> Erro ao finalizar a ligação de saída quando o formulário é fechado. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: $phoneCallId]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }
}
