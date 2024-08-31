package com.asaas.service.integration.callcenter

import com.asaas.integration.callcenter.api.CallCenterManager
import com.asaas.integration.callcenter.supportattendance.adapter.LinkProtocolListToAttendanceRequestAdapter
import com.asaas.integration.callcenter.supportattendance.adapter.UnlinkProtocolFromAttendanceRequestAdapter
import com.asaas.integration.callcenter.supportattendance.dto.LinkProtocolListToAttendanceRequestDTO
import com.asaas.integration.callcenter.supportattendance.dto.UnlinkProtocolFromAttendanceRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.LoggerUtils

import grails.transaction.Transactional

@Transactional
class CallCenterSupportAttendanceProtocolManagerService {

    def grailsApplication

    public void linkProtocolListToAttendance(LinkProtocolListToAttendanceRequestAdapter linkProtocolListToAttendanceRequestAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        LinkProtocolListToAttendanceRequestDTO linkProtocolListToAttendanceRequestDTO = new LinkProtocolListToAttendanceRequestDTO(linkProtocolListToAttendanceRequestAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendanceProtocol/linkProtocolListToAttendance", linkProtocolListToAttendanceRequestDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceProtocolManagerService.linkProtocolListToAttendance >> Falha ao vincular os protocolos de atendimento [${LoggerUtils.sanitizeParams(linkProtocolListToAttendanceRequestDTO.properties)}]")
            throw new RuntimeException("Não foi possível vincular os protocolos de atendimento. Por favor, tente novamente.")
        }
    }

    public void unlinkProtocolFromAttendance(UnlinkProtocolFromAttendanceRequestAdapter unlinkProtocolFromAttendanceAdapter) {
        if (grailsApplication.config.callcenter.phoneCall.testMode) return

        UnlinkProtocolFromAttendanceRequestDTO unlinkProtocolFromAttendanceDTO = new UnlinkProtocolFromAttendanceRequestDTO(unlinkProtocolFromAttendanceAdapter)

        CallCenterManager callCenterManager = new CallCenterManager()
        callCenterManager.post("/supportAttendanceProtocol/unlinkProtocolFromAttendance", unlinkProtocolFromAttendanceDTO)

        if (!callCenterManager.isSuccessful()) {
            AsaasLogger.error("CallCenterSupportAttendanceProtocolManagerService.unlinkProtocolFromAttendance >> Falha ao desvincular o protocolo do atendimento [${LoggerUtils.sanitizeParams(unlinkProtocolFromAttendanceDTO.properties)}]")
            throw new RuntimeException("Não foi possível desvincular o protocolo de atendimento. Por favor, tente novamente.")
        }
    }
}
