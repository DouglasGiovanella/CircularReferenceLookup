package com.asaas.service.integration.callcenter

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.phonecall.dto.CallCenterAnswerPhoneCallRequestDTO
import com.asaas.integration.callcenter.softphone.adapter.SoftphoneAttendanceInfoAdapter
import com.asaas.integration.callcenter.softphone.dto.CallCenterFindAttendanceInfoResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class CallCenterSoftphoneManagerService {

    def grailsApplication

    public void startWorking(Long asaasAccountManagerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/softphone/startWorking", [asaasAccountManagerId: asaasAccountManagerId])

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterSoftphoneManagerService.startWorking >> Falha ao iniciar trabalho do gerente. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [asaasAccountManagerId: $asaasAccountManagerId]]")

            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public void stopWorking(Long asaasAccountManagerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/softphone/stopWorking", [asaasAccountManagerId: asaasAccountManagerId])

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterSoftphoneManagerService.stopWorking >> Falha ao encerrar trabalho do gerente. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [asaasAccountManagerId: $asaasAccountManagerId]]")

            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public void stopWorkingWhenCloseSoftphone(Long asaasAccountManagerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/softphone/stopWorkingWhenCloseSoftphone", [asaasAccountManagerId: asaasAccountManagerId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSoftphoneManagerService.stopWorkingWhenCloseSoftphone >> Falha ao encerrar trabalho do gerente ao fechar softphone. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [asaasAccountManagerId: $asaasAccountManagerId]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public SoftphoneAttendanceInfoAdapter findAttendanceInfo(Long asaasAccountManagerId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/softphone/findAttendanceInfo", [asaasAccountManagerId: asaasAccountManagerId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSoftphoneManagerService.findAttendanceInfo >> Falha ao buscar dados do atendimento para softphone. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [asaasAccountManagerId: $asaasAccountManagerId]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }

        CallCenterFindAttendanceInfoResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterFindAttendanceInfoResponseDTO)
        return new SoftphoneAttendanceInfoAdapter(responseDTO)
    }

    public void answerOutgoingPhoneCall(Long phoneCallId, Long userId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterAnswerPhoneCallRequestDTO requestDTO = new CallCenterAnswerPhoneCallRequestDTO(phoneCallId, userId)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/softphone/answerOutgoingPhoneCall", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSoftphoneManagerService.answerOutgoingPhoneCall >> Falha ao atender ligação de saída. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [params: ${LoggerUtils.sanitizeParams(requestDTO.properties)}]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public void hangupWhenCallDroppedInMailbox(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/softphone/hangupWhenCallDroppedInMailbox", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSoftphoneManagerService.hangupWhenCallDroppedInMailbox >> Falha ao desligar ligação de saída que caiu na caixa postal. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: ${phoneCallId}]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }
}
