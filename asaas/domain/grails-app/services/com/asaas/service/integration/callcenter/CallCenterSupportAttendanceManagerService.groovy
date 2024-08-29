package com.asaas.service.integration.callcenter

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.supportattendance.adapter.ListSupportAttendanceRequestAdapter
import com.asaas.integration.callcenter.supportattendance.adapter.SupportAttendanceAdapter
import com.asaas.integration.callcenter.supportattendance.dto.CallCenterFindSupportAttendanceResponseDTO
import com.asaas.integration.callcenter.supportattendance.dto.ListSupportAttendanceRequestDTO
import com.asaas.integration.callcenter.supportattendance.dto.ListSupportAttendanceResponseDTO
import com.asaas.integration.callcenter.supportattendance.vo.CallCenterFindSupportAttendanceResponseVO
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.LoggerUtils

import grails.transaction.Transactional

@Transactional
class CallCenterSupportAttendanceManagerService {

    def grailsApplication

    public SequencedResultList<SupportAttendanceAdapter> list(ListSupportAttendanceRequestAdapter listSupportAttendanceRequestAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        ListSupportAttendanceRequestDTO listSupportAttendanceRequestDTO = new ListSupportAttendanceRequestDTO(listSupportAttendanceRequestAdapter)
        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendance/list", listSupportAttendanceRequestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceManagerService.list >> Falha ao listar os protocolos de atendimento [${LoggerUtils.sanitizeParams(listSupportAttendanceRequestDTO.properties)}]")
            throw new BusinessException("Não foi possível consultar os protocolos de atendimento. Por favor, tente novamente.")
        }

        Map supportAttendanceSequencedResultListMap = callCenterManager.responseBody.supportAttendanceSequencedResultList
        List<ListSupportAttendanceResponseDTO> listSupportAttendanceResponseDTOList = supportAttendanceSequencedResultListMap.list.collect { new ListSupportAttendanceResponseDTO(it) }

        return new SequencedResultList<>(
            listSupportAttendanceResponseDTOList.collect { new SupportAttendanceAdapter(it) },
            supportAttendanceSequencedResultListMap.max,
            supportAttendanceSequencedResultListMap.offset,
            supportAttendanceSequencedResultListMap.hasPreviousPage,
            supportAttendanceSequencedResultListMap.hasNextPage)
    }

    public List<SupportAttendanceAdapter> listForLinkingProtocolToAttendance(ListSupportAttendanceRequestAdapter supportAttendanceRequestAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return []

        ListSupportAttendanceRequestDTO supportAttendanceRequestDTO = new ListSupportAttendanceRequestDTO(supportAttendanceRequestAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendance/listForLinkingProtocolToAttendance", supportAttendanceRequestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceManagerService.listForLinkingProtocolToAttendance >> Falha ao listar os protocolos de atendimento [${LoggerUtils.sanitizeParams(supportAttendanceRequestDTO.properties)}]")
            throw new BusinessException("Não foi possível listar os protocolos de atendimento: ${callCenterManager.getErrorMessage()}")
        }

        List<Map> responseDTO = callCenterManager.responseBody.responseDTO
        if (!responseDTO) return []

        List<ListSupportAttendanceResponseDTO> supportAttendanceResponseDTOList = responseDTO.collect { new ListSupportAttendanceResponseDTO(it) }

        return supportAttendanceResponseDTOList.collect { new SupportAttendanceAdapter(it) }
    }

    public CallCenterFindSupportAttendanceResponseVO find(Long supportAttendanceId) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return new CallCenterFindSupportAttendanceResponseDTO([:])

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.get("/supportAttendance/find", [supportAttendanceId: supportAttendanceId])

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceManagerService.find >> Falha ao buscar protocolo de atendimento [supportAttendanceId: ${supportAttendanceId}]")
            throw new BusinessException("Não foi possível buscar o protocolo de atendimento. Por favor, tente novamente.")
        }

        CallCenterFindSupportAttendanceResponseDTO callCenterFindSupportAttendanceResponseDTO = new CallCenterFindSupportAttendanceResponseDTO(callCenterManager.responseBody.asaasFindSupportAttendanceResponseDTO)

        return new CallCenterFindSupportAttendanceResponseVO(callCenterFindSupportAttendanceResponseDTO)
    }
}
