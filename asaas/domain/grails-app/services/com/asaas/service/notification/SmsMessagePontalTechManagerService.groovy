package com.asaas.service.notification

import com.asaas.integration.sms.adapter.SmsMessageAdapter
import com.asaas.integration.sms.pontaltech.adapter.PontalTechSmsDTO
import com.asaas.integration.sms.pontaltech.adapter.PontalTechSmsResponseDTO
import com.asaas.integration.sms.pontaltech.manager.PontalTechManager
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class SmsMessagePontalTechManagerService {

    public SmsMessageAdapter sendNotification(String message, String toPhone, Map options) {
        PontalTechManager pontalTechManager = new PontalTechManager()
        PontalTechSmsDTO pontalTechSmsDTO = new PontalTechSmsDTO(options.from ? "${options.from}: ${message}" : message, toPhone)
        Map requestMap = pontalTechSmsDTO.toMap()

        SmsMessageAdapter smsMessageAdapter = new SmsMessageAdapter()
        smsMessageAdapter.requestMap = requestMap
        smsMessageAdapter.requestTime = new Date()

        pontalTechManager.post(PontalTechManager.SINGLE_SMS, requestMap)

        String response = (pontalTechManager.responseBody as JSON).toString()
        smsMessageAdapter.responseBody = response
        smsMessageAdapter.responseTime = new Date()

        PontalTechSmsResponseDTO pontalTechSmsResponseDTO
        try {
            pontalTechSmsResponseDTO = GsonBuilderUtils.buildClassFromJson(response, PontalTechSmsResponseDTO) as PontalTechSmsResponseDTO
        } catch (Exception exception) {
            AsaasLogger.confidencial("SmsMessagePontalTechManagerService.sendNotification >> ${exception.getClass()} ao enviar SmsMessageID: [${options.id}] destino: [${toPhone}] Mensagem: [${message}] Request Body [${requestMap.toString()}] Response Body [${pontalTechManager?.responseBody?.toString()}] Response Header [${pontalTechManager?.httpRequestManager?.responseHeaderMap?.toString()}] Exception Message [${exception.getMessage()}]", "sms_message_log")
            throw exception
        }

        if (pontalTechManager.isSuccessful()) {
            smsMessageAdapter.id = pontalTechSmsResponseDTO.id
            smsMessageAdapter.status = Status.SUCCESS
        } else {
            if (pontalTechManager.isConnectionFailure()) {
                smsMessageAdapter.status = Status.FAILED
                smsMessageAdapter.exception = new Exception(pontalTechManager.getErrorMessage())
            } else {
                smsMessageAdapter.status = Status.ERROR
            }

            smsMessageAdapter.errorMessage = "${ pontalTechSmsResponseDTO.error?.code } - ${ pontalTechSmsResponseDTO.error?.message }"
        }

        return smsMessageAdapter
    }
}
