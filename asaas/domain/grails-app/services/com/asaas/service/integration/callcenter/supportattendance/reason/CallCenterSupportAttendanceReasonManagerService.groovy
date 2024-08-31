package com.asaas.service.integration.callcenter.supportattendance.reason

import com.asaas.exception.BusinessException
import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.supportattendance.reason.adapter.SupportAttendanceReasonAdapter
import com.asaas.integration.callcenter.supportattendance.reason.adapter.SupportAttendanceReasonListFilterAdapter
import com.asaas.integration.callcenter.supportattendance.reason.dto.CallCenterListSupportAttendanceReasonRequestDTO
import com.asaas.integration.callcenter.supportattendance.reason.dto.CallCenterListSupportAttendanceReasonResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.LoggerUtils

import grails.converters.JSON
import grails.transaction.Transactional

import org.springframework.http.HttpStatus

@Transactional
class CallCenterSupportAttendanceReasonManagerService {

    def grailsApplication
    def supportAttendanceReasonCacheService

    public SequencedResultList<SupportAttendanceReasonAdapter> list(SupportAttendanceReasonListFilterAdapter filterAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return null

        CallCenterListSupportAttendanceReasonRequestDTO requestDTO = new CallCenterListSupportAttendanceReasonRequestDTO(filterAdapter)
        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendanceReason/list", requestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceReasonManagerService.list >> Falha ao listar motivos de atendimento [${LoggerUtils.sanitizeParams(requestDTO.properties)}]")
            throw new RuntimeException("Não foi possível consultar os motivos de atendimento. Por favor, tente novamente.")
        }

        CallCenterListSupportAttendanceReasonResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((callCenterManager.responseBody as JSON).toString(), CallCenterListSupportAttendanceReasonResponseDTO)

        return new SequencedResultList<>(
            responseDTO.list.collect { new SupportAttendanceReasonAdapter(it) },
            responseDTO.max,
            responseDTO.offset,
            responseDTO.hasPreviousPage,
            responseDTO.hasNextPage)
    }

    public void save(String description) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendanceReason/save", [description: description])

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterSupportAttendanceReasonManagerService.save >> Falha ao salvar motivo de atendimento [description: $description]. Erro: ${callCenterManager.getErrorMessage()}")

            throw new RuntimeException("Não foi possível salvar o motivo de atendimento. Por favor, tente novamente.")
        }

        supportAttendanceReasonCacheService.evict()
    }

    public void delete(Long id) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendanceReason/disable", [id: id])

        if (!callCenterManager.isSuccessful()) {
            if (callCenterManager.status == HttpStatus.BAD_REQUEST.value()) throw new BusinessException("${callCenterManager.getErrorMessage()}")

            AsaasLogger.error("CallCenterSupportAttendanceReasonManagerService.delete >> Falha ao remover motivo de atendimento [id: $id]. Erro: ${callCenterManager.getErrorMessage()}")

            throw new RuntimeException("Não foi possível remover o motivo de atendimento. Por favor, tente novamente.")
        }

        supportAttendanceReasonCacheService.evict()
    }
}
