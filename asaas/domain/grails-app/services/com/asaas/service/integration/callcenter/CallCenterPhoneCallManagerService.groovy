package com.asaas.service.integration.callcenter

import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.phonecall.adapter.ListPhoneCallAdapter
import com.asaas.integration.callcenter.phonecall.adapter.incomingcallform.PhoneCallAdapter
import com.asaas.integration.callcenter.phonecall.dto.CallCenterListPhoneCallRequestDTO
import com.asaas.integration.callcenter.phonecall.dto.CallCenterListPhoneCallResponseDTO
import com.asaas.integration.callcenter.phonecall.dto.children.PhoneCallDTO
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CallCenterPhoneCallManagerService {

    def grailsApplication

    public SequencedResultList<PhoneCallAdapter> list(ListPhoneCallAdapter listPhoneCallAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterListPhoneCallRequestDTO requestDTO = new CallCenterListPhoneCallRequestDTO(listPhoneCallAdapter)
        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/phoneCall/list", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterPhoneCallManagerService.list >> Falha ao listar ligações [${LoggerUtils.sanitizeParams(requestDTO.properties)}]")
            throw new RuntimeException("Não foi possível consultar as ligações. Por favor, tente novamente.")
        }

        CallCenterListPhoneCallResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterListPhoneCallResponseDTO)

        return new SequencedResultList<>(
            responseDTO.list.collect { new PhoneCallAdapter(it) },
            responseDTO.max,
            responseDTO.offset,
            responseDTO.hasPreviousPage,
            responseDTO.hasNextPage)
    }

    public PhoneCallAdapter find(Long phoneCallId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/phoneCall/find", [phoneCallId: phoneCallId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterPhoneCallManagerService.find >> Falha ao buscar ligação [${phoneCallId}]")
            throw new RuntimeException("Não foi possível consultar a ligação. Por favor, tente novamente.")
        }

        PhoneCallDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), PhoneCallDTO)

        return new PhoneCallAdapter(responseDTO)
    }
}
