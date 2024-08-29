package com.asaas.service.integration.heimdall

import com.asaas.domain.customer.Customer
import com.asaas.integration.heimdall.dto.documentanalysis.get.common.document.DocumentFileDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.identification.IdentificationDocumentAnalysisResponseDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.identification.children.IdentificationDocumentAnalysisItemResponseDTO
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class IdentificationDocumentAnalysisApprovalService {

    def identificationDocumentAnalysisManagerService
    def authorizationDeviceUpdateRequestService

    public void process(Long identificationDocumentAnalysisId) {
        IdentificationDocumentAnalysisResponseDTO analysis = identificationDocumentAnalysisManagerService.getById(identificationDocumentAnalysisId)
        if (!analysis || !analysis.type.isAuthorizationDevice()) {
            AsaasLogger.error("Não foi possível buscar a análise de documentos de identificação para a aprovação automática. Id [${identificationDocumentAnalysisId}]")
            return
        }

        if (!analysis.status.isApproved()) return

        Customer customer = Customer.get(analysis.accountId)
        Long documentFileId
        analysis.items.each { IdentificationDocumentAnalysisItemResponseDTO item ->
            item.document.files.each { DocumentFileDTO documentFileDto ->
                if (documentFileDto.type.isSelfie()) {
                    documentFileId = documentFileDto.id
                }
            }
        }

        authorizationDeviceUpdateRequestService.approveByAutomaticAnalysis(customer, documentFileId)
    }
}
