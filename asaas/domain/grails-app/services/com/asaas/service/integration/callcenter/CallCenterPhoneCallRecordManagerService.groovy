package com.asaas.service.integration.callcenter

import com.asaas.callcenter.phonecallrecord.adapter.PhoneCallRecordAdapter
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.phonecallrecord.adapter.PhoneCallRecordListFilterAdapter
import com.asaas.integration.callcenter.phonecallrecord.dto.CallCenterListPhoneCallRecordRequestDTO
import com.asaas.integration.callcenter.phonecallrecord.dto.CallCenterListPhoneCallRecordResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CallCenterPhoneCallRecordManagerService {

    def grailsApplication

    public SequencedResultList<PhoneCallRecordAdapter> list(PhoneCallRecordListFilterAdapter phoneCallRecordListFilterAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterListPhoneCallRecordRequestDTO requestDTO = new CallCenterListPhoneCallRecordRequestDTO(phoneCallRecordListFilterAdapter)
        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/phoneCallRecord/list", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterPhoneCallRecordManagerService.list >> Falha ao listar gravações [${LoggerUtils.sanitizeParams(requestDTO.properties)}]")
            throw new RuntimeException("Não foi possível consultar as gravações. Por favor, tente novamente.")
        }

        CallCenterListPhoneCallRecordResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterListPhoneCallRecordResponseDTO)

        return new SequencedResultList<>(
            responseDTO.list.collect { new PhoneCallRecordAdapter(it) },
            responseDTO.max,
            responseDTO.offset,
            responseDTO.hasPreviousPage,
            responseDTO.hasNextPage)
    }
}
