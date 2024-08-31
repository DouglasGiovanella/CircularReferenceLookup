package com.asaas.service.externalonboarding

import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.thirdpartydocumentationonboarding.dto.SaveExternalDocumentResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyIdentificationCustomerDocumentAdapter
import com.asaas.thirdpartydocumentationonboarding.adapter.ThirdPartyIdentificationCustomerDocumentFileAdapter
import com.asaas.thirdpartydocumentationonboarding.enums.ThirdPartyIdentificationCustomerDocumentFileType
import com.asaas.thirdpartydocumentationonboarding.vo.CustomerDocumentFileVO
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ThirdPartyIdentificationCustomerDocumentService {

    def customerDocumentFileService
    def customerDocumentGroupService
    def customerDocumentAnalysisStatusService
    def customerRegisterStatusService

    public SaveExternalDocumentResponseDTO proccessDocumentFiles(ThirdPartyIdentificationCustomerDocumentAdapter identificationCustomerDocumentAdapter) {
        SaveExternalDocumentResponseDTO documentResponseDTO

        Utils.withNewTransactionAndRollbackOnError({
            Customer customer = Customer.get(identificationCustomerDocumentAdapter.accountId)

            if (!customer) {
                AsaasLogger.error("ThirdPartyIdentificationCustomerDocumentService.proccessDocumentFiles >> Erro ao processar documento AccountId:[${identificationCustomerDocumentAdapter.accountId}]")
                return
            }

            if (customer.accountDisabled()) {
                AsaasLogger.error("ThirdPartyIdentificationCustomerDocumentService.proccessDocumentFiles >> Erro ao processar documento, conta desabilitada. AccountId:[${identificationCustomerDocumentAdapter.accountId}]")
                return
            }

            CustomerDocumentGroupType identificationGroupType = customerDocumentGroupService.getIdentificationGroupType(customer)

            CustomerDocumentGroup identificationGroup = customerDocumentGroupService.save(customer, identificationGroupType)

            ThirdPartyIdentificationCustomerDocumentFileAdapter frontIdentificationFile = identificationCustomerDocumentAdapter.documentFilesList.find { it.type == ThirdPartyIdentificationCustomerDocumentFileType.FRONT }
            ThirdPartyIdentificationCustomerDocumentFileAdapter backIdentificationFile = identificationCustomerDocumentAdapter.documentFilesList.find { it.type == ThirdPartyIdentificationCustomerDocumentFileType.BACK }
            ThirdPartyIdentificationCustomerDocumentFileAdapter selfieFile = identificationCustomerDocumentAdapter.documentFilesList.find { it.type == ThirdPartyIdentificationCustomerDocumentFileType.SELFIE }

            List<CustomerDocumentFileVO> savedCustomerDocumentFilesVO = []

            savedCustomerDocumentFilesVO.addAll(saveExternalDocumentFiles(identificationGroup, CustomerDocumentType.IDENTIFICATION, [frontIdentificationFile, backIdentificationFile]))
            savedCustomerDocumentFilesVO.addAll(saveExternalDocumentFiles(identificationGroup, CustomerDocumentType.IDENTIFICATION_SELFIE, [selfieFile]))

            CustomerDocument identificationCustomerDocument = CustomerDocument.query([group: identificationGroup, type: CustomerDocumentType.IDENTIFICATION]).get()
            documentResponseDTO = new SaveExternalDocumentResponseDTO(identificationCustomerDocument, savedCustomerDocumentFilesVO)

            updateDocumentStatusToAwaitingApprovalIfPossible(customer)
        }, [logErrorMessage: "ThirdPartyIdentificationCustomerDocumentService.proccessDocumentFiles >> Erro ao processar documento AccountId:[${identificationCustomerDocumentAdapter.accountId}]"])

        return documentResponseDTO
    }

    private updateDocumentStatusToAwaitingApprovalIfPossible(Customer customer) {
        Map hasCustomerDocumentNotSentOrRejected = customerDocumentGroupService.checkIfhasCustomerDocumentNotSentOrRejected(customer)
        if (hasCustomerDocumentNotSentOrRejected.hasCustomerDocumentNotSent) return

        customerRegisterStatusService.updateDocumentStatus(customer, Status.AWAITING_APPROVAL)
        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, DocumentAnalysisStatus.AWAITING_AUTOMATIC_ANALYSIS)
    }

    private List<CustomerDocumentFileVO> saveExternalDocumentFiles(CustomerDocumentGroup group, CustomerDocumentType customerDocumentType, List<ThirdPartyIdentificationCustomerDocumentFileAdapter> thirdPartyFileAdapterList) {
        CustomerDocument savedCustomerDocument = CustomerDocument.query([group: group, type: customerDocumentType]).get()
        if (!savedCustomerDocument) return []

        savedCustomerDocument.status = CustomerDocumentStatus.PENDING
        savedCustomerDocument.save(flush: true, failOnError: true)

        List<CustomerDocumentFileVO> customerDocumentFilesVO = []

        for (ThirdPartyIdentificationCustomerDocumentFileAdapter thirdPartyFileAdapter : thirdPartyFileAdapterList) {
            if (!thirdPartyFileAdapter) continue

            CustomerDocumentFile savedCustomerDocumentFile = customerDocumentFileService.save(savedCustomerDocument, thirdPartyFileAdapter)
            CustomerDocumentFileVO customerDocumentFileVO = new CustomerDocumentFileVO(savedCustomerDocumentFile, thirdPartyFileAdapter.type)
            customerDocumentFilesVO.add(customerDocumentFileVO)
        }

        return customerDocumentFilesVO
    }
}
