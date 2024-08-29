package com.asaas.service.notification

import com.asaas.domain.sms.SmsMessageProvider
import com.asaas.integration.sms.adapter.SmsMessageAdapter
import com.asaas.integration.sms.sinch.adapter.SinchSmsDTO
import com.asaas.integration.sms.sinch.adapter.SinchSmsResponseDTO
import com.asaas.status.Status
import com.asaas.integration.sms.sinch.manager.SinchManager
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class SmsMessageSinchManagerService {

    public SmsMessageAdapter sendNotification(String message, String toPhoneNumber, SmsMessageProvider sinchProvider, Map options) {
        SinchManager sinchManager = new SinchManager(sinchProvider)
        SinchSmsDTO sinchSmsDTO = new SinchSmsDTO(options.from ? "${ options.from }: ${ message }" : message, toPhoneNumber)
        Map requestMap = sinchSmsDTO.toMap()

        SmsMessageAdapter smsMessageAdapter = new SmsMessageAdapter()
        smsMessageAdapter.requestMap = requestMap
        smsMessageAdapter.requestTime = new Date()

        sinchManager.post(SinchManager.SINGLE_SMS, requestMap)

        String response = (sinchManager.responseBody as JSON).toString()
        smsMessageAdapter.responseBody = response
        smsMessageAdapter.responseTime = new Date()

        SinchSmsResponseDTO sinchSmsResponseDTO = GsonBuilderUtils.buildClassFromJson(response, SinchSmsResponseDTO) as SinchSmsResponseDTO

        if (sinchManager.isSuccessful()) {
            smsMessageAdapter.id = sinchSmsResponseDTO.id
            smsMessageAdapter.status = Status.SUCCESS
            return smsMessageAdapter
        }

        if (sinchManager.isConnectionFailure()) {
            smsMessageAdapter.status = Status.FAILED
            smsMessageAdapter.exception = new Exception(sinchManager.getErrorMessage())
            return smsMessageAdapter
        }

        smsMessageAdapter.status = Status.ERROR
        smsMessageAdapter.errorMessage = "${ sinchSmsResponseDTO.errorCode } - ${ sinchSmsResponseDTO.errorMessage }"
        return smsMessageAdapter
    }
}
