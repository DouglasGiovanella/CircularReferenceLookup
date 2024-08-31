package com.asaas.service.integration.jdpsti

import com.asaas.domain.customer.Customer
import com.asaas.integration.jdpsti.adapter.BeneficiaryResponseAdapter
import com.asaas.integration.jdpsti.adapter.BeneficiaryStatusResponseAdapter
import com.asaas.integration.jdpsti.api.beneficiary.JdPstiBeneficiaryManager
import com.asaas.integration.jdpsti.builder.JdPstiBeneficiaryBuilder
import com.asaas.integration.jdpsti.dto.JdPstiConsultBeneficiaryRequestDTO
import com.asaas.integration.jdpsti.dto.JdPstiDeleteBeneficiaryRequestDTO
import com.asaas.integration.jdpsti.dto.JdPstiRegisterBeneficiaryRequestDTO
import com.asaas.integration.jdpsti.dto.JdPstiUpdateBeneficiaryStatusRequestDTO
import com.asaas.integration.jdpsti.enums.JdPstiRequestType
import com.asaas.integration.jdpsti.parser.JdPstiBeneficiaryResponseParser
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class JdPstiBeneficiaryManagerService {

    private final static String PATH = "/bnf/v1/reqcip"

    def asyncActionService

    public BeneficiaryResponseAdapter registerOrUpdate(Customer customer, Boolean existsBeneficiaryWithCpfCnpj, Boolean removeAgreement) {
        JdPstiRegisterBeneficiaryRequestDTO beneficiaryRequestDTO = JdPstiBeneficiaryBuilder.buildRegisterRequestFromCustomer(customer, existsBeneficiaryWithCpfCnpj, removeAgreement)

        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        beneficiaryManager.post("${JdPstiBeneficiaryManagerService.PATH}${existsBeneficiaryWithCpfCnpj ? "/alt":"/inc"}", clearInvalidRequestProperties(beneficiaryRequestDTO.properties))
        BeneficiaryResponseAdapter registerResponse = JdPstiBeneficiaryResponseParser.parseRegisterResponse(beneficiaryRequestDTO.NumCtrlPart, beneficiaryManager)

        if (registerResponse.errorData) return registerResponse

        registerResponse = updateRequestStatus(registerResponse)

        if (registerResponse.beneficiaryStatus.isPending()) asyncActionService.saveBeneficiaryRegisterWaitingConfirmation([requestType: JdPstiRequestType.REGISTER, customerId: customer.id, partnerControlNumber: registerResponse.externalIdentifier, requestId: registerResponse.requestId])

        return registerResponse
    }

    public BeneficiaryResponseAdapter updateStatus(Customer customer, String status) {
        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        JdPstiUpdateBeneficiaryStatusRequestDTO jdPstiUpdateBeneficiaryStatusRequestDTO = new JdPstiUpdateBeneficiaryStatusRequestDTO(customer, status)
        beneficiaryManager.post("${JdPstiBeneficiaryManagerService.PATH}/situacaobnf", clearInvalidRequestProperties(jdPstiUpdateBeneficiaryStatusRequestDTO.properties))

        BeneficiaryResponseAdapter updateResponse = JdPstiBeneficiaryResponseParser.parseRequestResponse(jdPstiUpdateBeneficiaryStatusRequestDTO.NumCtrlPart, beneficiaryManager)

        if (updateResponse.errorData) return  updateResponse

        updateResponse = updateRequestStatus(updateResponse)

        return updateResponse
    }

    public BeneficiaryResponseAdapter remove(Customer customer) {
        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        JdPstiDeleteBeneficiaryRequestDTO jdPstiDeleteBeneficiaryRequestDTO = new JdPstiDeleteBeneficiaryRequestDTO(customer)
        beneficiaryManager.post("${JdPstiBeneficiaryManagerService.PATH}/exc", clearInvalidRequestProperties(jdPstiDeleteBeneficiaryRequestDTO.properties))

        BeneficiaryResponseAdapter deleteResponse = JdPstiBeneficiaryResponseParser.parseRequestResponse(jdPstiDeleteBeneficiaryRequestDTO.NumCtrlPart, beneficiaryManager)
        if (deleteResponse.errorData) return deleteResponse

        deleteResponse = updateRequestStatus(deleteResponse)

        if (deleteResponse.beneficiaryStatus.isPending()) asyncActionService.saveBeneficiaryRemovalWaitingConfirmation([requestType: JdPstiRequestType.DELETE, customerId: customer.id, partnerControlNumber: deleteResponse.externalIdentifier, requestId: deleteResponse.requestId])

        return deleteResponse
    }

    public BeneficiaryResponseAdapter processCipRequestStatusUpdate(String partnerControlNumber, String requestId) {
        BeneficiaryResponseAdapter statusResponse = getStatus(partnerControlNumber)
        statusResponse.externalIdentifier = partnerControlNumber
        statusResponse.requestId = requestId

        return statusResponse
    }

    public BeneficiaryResponseAdapter updateBeneficiaryAsInapt(Customer customer) {
        return updateStatus(customer, JdPstiBeneficiaryResponseParser.BENEFICIARY_STATUS_INAPT)
    }

    public BeneficiaryResponseAdapter updateBeneficiaryAsApt(Customer customer) {
        return updateStatus(customer, JdPstiBeneficiaryResponseParser.BENEFICIARY_STATUS_APT)
    }

    public BeneficiaryResponseAdapter updateBeneficiaryAsUnderAnalisys(Customer customer) {
        return updateStatus(customer, JdPstiBeneficiaryResponseParser.BENEFICIARY_STATUS_UNDER_ANALISYS)
    }

    public BeneficiaryStatusResponseAdapter getBeneficiaryStatus(Customer customer) {
        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        JdPstiConsultBeneficiaryRequestDTO beneficiaryRequestDTO = new JdPstiConsultBeneficiaryRequestDTO(customer)
        beneficiaryManager.post("${JdPstiBeneficiaryManagerService.PATH}/consultabnf", clearInvalidRequestProperties(beneficiaryRequestDTO.properties))

        BeneficiaryStatusResponseAdapter statusResponse = JdPstiBeneficiaryResponseParser.parseFromDetailedXMLResponse(beneficiaryManager)
        if (!statusResponse.errorData) {
            statusResponse = getStatusFromXmlDetail(beneficiaryRequestDTO.NumCtrlPart)
            if (JdPstiBeneficiaryResponseParser.isExternalIdentifierNotFoundError(beneficiaryRequestDTO.NumCtrlPart, statusResponse.errorData)) {
                AsaasLogger.warn("JdPstiBeneficiaryManagerService.getBeneficiaryStatus >> Retry getStatusFromXmlDetail for externalIdentifier [${beneficiaryRequestDTO.NumCtrlPart}]")
                statusResponse = getStatusFromXmlDetail(beneficiaryRequestDTO.NumCtrlPart)
            }
        }

        return statusResponse
    }

    private BeneficiaryResponseAdapter updateRequestStatus(BeneficiaryResponseAdapter responseAdapter) {
        BeneficiaryResponseAdapter statusResponse = getStatus(responseAdapter.externalIdentifier)
        if (statusResponse) {
            if (JdPstiBeneficiaryResponseParser.isExternalIdentifierNotFoundError(responseAdapter.externalIdentifier, statusResponse.errorData)) {
                AsaasLogger.warn("JdPstiBeneficiaryManagerService.updateRequestStatus >> Retry getStatus for externalIdentifier [${responseAdapter.externalIdentifier}]")
                statusResponse = getStatus(responseAdapter.externalIdentifier)
            }

            responseAdapter.errorData = statusResponse.errorData
            responseAdapter.beneficiaryStatus = statusResponse.beneficiaryStatus
        }

        return responseAdapter
    }

    private BeneficiaryResponseAdapter getStatus(String externalIdentifier) {
        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        beneficiaryManager.get("${JdPstiBeneficiaryManagerService.PATH}/${externalIdentifier}", null)

        return JdPstiBeneficiaryResponseParser.parseRequestResponse(externalIdentifier, beneficiaryManager)
    }

    private BeneficiaryStatusResponseAdapter getStatusFromXmlDetail(String externalIdentifier) {
        JdPstiBeneficiaryManager beneficiaryManager = new JdPstiBeneficiaryManager()
        beneficiaryManager.get("${JdPstiBeneficiaryManagerService.PATH}/XML/${externalIdentifier}", null)

        return JdPstiBeneficiaryResponseParser.parseFromDetailedXMLResponse(beneficiaryManager)
    }

    private Map clearInvalidRequestProperties(Map requestBodyMap) {
        requestBodyMap.remove("class")

        return requestBodyMap
    }
}
