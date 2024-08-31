package com.asaas.service.integration.callcenter

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.phonecall.adapter.AnswerIncomingCallAdapter
import com.asaas.integration.callcenter.phonecall.adapter.FinishIncomingCallAttendanceAdapter
import com.asaas.integration.callcenter.phonecall.adapter.incomingcallform.IncomingCallFormAdapter
import com.asaas.integration.callcenter.phonecall.dto.CallCenterAnswerPhoneCallRequestDTO
import com.asaas.integration.callcenter.phonecall.dto.CallCenterAnswerPhoneCallResponseDTO
import com.asaas.integration.callcenter.phonecall.dto.CallCenterFindIncomingCallInfoResponseDTO
import com.asaas.integration.callcenter.phonecall.dto.CallCenterFinishIncomingCallAttendanceRequestDTO
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter
import com.asaas.integration.callcenter.supportattendance.dto.CallCenterFinishAttendanceResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class CallCenterIncomingCallManagerService {

    def grailsApplication

    public AnswerIncomingCallAdapter answer(Long phoneCallId, Long userId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return new AnswerIncomingCallAdapter(true, "")
        if (!phoneCallId) return new AnswerIncomingCallAdapter(false, "A ligação retornou para a fila, pois houve demora para atendê-la")

        CallCenterAnswerPhoneCallRequestDTO requestDTO = new CallCenterAnswerPhoneCallRequestDTO(phoneCallId, userId)
        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/incomingCallInteraction/answer", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterIncomingCallManagerService.answer >> Falha ao atender ligação de entrada no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: ${LoggerUtils.sanitizeParams(requestDTO.properties)}]")
            return new AnswerIncomingCallAdapter(false, "Falha ao atender a ligação, tente novamente")
        }

        CallCenterAnswerPhoneCallResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterAnswerPhoneCallResponseDTO)
        return new AnswerIncomingCallAdapter(responseDTO)
    }

    public IncomingCallFormAdapter findIncomingCallInfo(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) throw new RuntimeException("Não é possível abrir o formulário de ligação com o testMode ativo")

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/incomingCallInteraction/findIncomingCallInfo", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterIncomingCallManagerService.findIncomingCallInfo >> Falha ao consultar informações da ligação de entrada no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: $phoneCallId]]")
            throw new RuntimeException("Falha ao consultar informações da ligação de entrada")
        }

        CallCenterFindIncomingCallInfoResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterFindIncomingCallInfoResponseDTO)
        return new IncomingCallFormAdapter(responseDTO)
    }

    public SupportAttendanceAdapter finishAttendance(FinishIncomingCallAttendanceAdapter finishIncomingCallAttendanceAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterFinishIncomingCallAttendanceRequestDTO requestDTO = new CallCenterFinishIncomingCallAttendanceRequestDTO(finishIncomingCallAttendanceAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/incomingCallInteraction/finishAttendance", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterIncomingCallManagerService.finishAttendance >> Falha ao finalizar a ligação no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: ${LoggerUtils.sanitizeParams(requestDTO.properties)}]")

            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }

        CallCenterFinishAttendanceResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterFinishAttendanceResponseDTO)
        return new SupportAttendanceAdapter(responseDTO)
    }

    public void finishAttendanceWhenConnectionDropped(FinishIncomingCallAttendanceAdapter finishIncomingCallAttendanceAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterFinishIncomingCallAttendanceRequestDTO requestDTO = new CallCenterFinishIncomingCallAttendanceRequestDTO(finishIncomingCallAttendanceAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/incomingCallInteraction/finishAttendanceWhenConnectionDropped", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterIncomingCallManagerService.finishAttendanceWhenConnectionDropped >> Falha ao finalizar quando a ligação caiu no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: ${LoggerUtils.sanitizeParams(requestDTO.properties)}]")

            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }

    public void finishAttendanceWhenCloseFormWindow(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/incomingCallInteraction/finishAttendanceWhenCloseFormWindow", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterIncomingCallManagerService.finishAttendanceWhenCloseFormWindow >> Falha ao fechar janela do formulário no Call Center. [StatusCode: ${callCenterManager.statusCode}, ResponseBody: ${LoggerUtils.sanitizeParams(callCenterManager.responseBody)}, RequestBody: [phoneCallId: $phoneCallId]]")
            throw new RuntimeException("${callCenterManager.getErrorMessage()}")
        }
    }
}
