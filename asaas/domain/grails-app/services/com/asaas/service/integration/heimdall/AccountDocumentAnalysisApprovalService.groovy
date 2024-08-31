package com.asaas.service.integration.heimdall

import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.documentanalysis.DocumentAnalysisRejectReason
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.integration.heimdall.dto.documentanalysis.get.account.AccountDocumentAnalysisResponseDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.account.children.AccountDocumentAnalysisItemResponseDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.common.automaticidentificationreport.AutomaticIdentificationReportResponseDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.common.document.DocumentDTO
import com.asaas.integration.heimdall.dto.documentanalysis.get.common.document.DocumentFileDTO
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class AccountDocumentAnalysisApprovalService {

    def accountDocumentAnalysisManagerService
    def customerGeneralAnalysisService
    def documentAnalysisService
    def revenueServiceRegisterService

    public void process(Long accountDocumentAnalysisId) {
        AccountDocumentAnalysisResponseDTO analysis = accountDocumentAnalysisManagerService.getById(accountDocumentAnalysisId)
        if (!analysis) {
            AsaasLogger.error("Não foi possível buscar a análise de documentos da conta para a aprovação automática. Id [${accountDocumentAnalysisId}]")
            return
        }

        Customer customer = Customer.read(analysis.accountId)

        BusinessValidation validatedCanBeAnalyzedAutomatically = canBeAnalyzedAutomatically(analysis, customer)
        if (!validatedCanBeAnalyzedAutomatically.isValid()) {
            documentAnalysisService.saveManualAnalysisRequired(customer, "Motivo: " + validatedCanBeAnalyzedAutomatically.asaasErrors[0].code)
            return
        }

        if (analysis.status.isApproved()) {
            approveAccountDocumentAutomaticallyIfPossible(analysis, customer)
            return
        }

        if (analysis.status.isRejected()) {
            processRejectionIfPossible(analysis, customer)
        }
    }

    private void processRejectionIfPossible(AccountDocumentAnalysisResponseDTO analysis, Customer customer) {
        Map customerDocuments = getCustomerDocuments(analysis)
        List<CustomerDocument> customerDocumentList = customerDocuments.customerDocumentList
        List<CustomerDocumentFile> customerDocumentFileList = customerDocuments.customerDocumentFileList

        if (analysis.shouldRejectAccountAutomatically) {
            CustomerGeneralAnalysisRejectReason rejectReason = getGeneralAnalysisRejectReason(analysis)
            customerGeneralAnalysisService.rejectAutomaticallyIfPossible(customer, rejectReason)
            if (customer.customerRegisterStatus.generalApproval.isRejected()) return
        }

        if (analysis.shouldRejectAutomatically) {
            DocumentAnalysisRejectReason rejectReason = getDocumentAnalysisRejectReason(analysis)

            List<Long> customerDocumentIdList = customerDocumentList.collect({ it.id })
            List<Long> customerDocumentFileIdList = customerDocumentFileList.collect({ it.id })

            documentAnalysisService.rejectDocumentsAutomaticallyByRejectReason(customer, null, rejectReason, customerDocumentIdList, customerDocumentFileIdList)
            return
        }

        if (isValidForAutomaticApproval(analysis, customer)) {
            approveAccountDocumentAutomaticallyIfPossible(analysis, customer)
            AsaasLogger.info("AccountDocumentAnalysisApprovalService.processRejectionIfPossible >> Documentação aprovada automaticamente através do fluxo de reanálise. [Customer = ${customer.id}]")
        }
    }

    private void approveAccountDocumentAutomaticallyIfPossible(AccountDocumentAnalysisResponseDTO analysis, Customer customer) {
        Map customerDocuments = getCustomerDocuments(analysis)
        List<CustomerDocument> customerDocumentList = customerDocuments.customerDocumentList

        if (!customerDocumentList) return
        List<CustomerDocumentFile> customerDocumentFileList = customerDocuments.customerDocumentFileList

        DocumentAnalysisStatus status = hasOtherNotApprovedDocument(customerDocumentList, customer) ?
            DocumentAnalysisStatus.ACCOUNT_DOCUMENT_ANALYSIS_APPROVED : DocumentAnalysisStatus.APPROVED
        Map analysisData = buildAnalysisData(analysis)

        List<Long> customerDocumentIdList = customerDocumentList.collect({ it.id })
        List<Long> customerDocumentFileIdList = customerDocumentFileList.collect({ it.id })

        documentAnalysisService.approveAccountDocumentAutomatically(customer, customerDocumentIdList, customerDocumentFileIdList, analysisData, status)
    }

    private BusinessValidation canBeAnalyzedAutomatically(AccountDocumentAnalysisResponseDTO analysis, Customer customer) {
        BusinessValidation validateBusiness = new BusinessValidation()

        if (!accountDocumentIsPending(analysis)) {
            validateBusiness.addError("Não há nenhum documento pendente.")
            return validateBusiness
        }

        if (analysis.status.isRejected()) {
            if (analysis.shouldRejectAutomatically) return validateBusiness
            if (analysis.shouldRejectAccountAutomatically && customer.isNaturalPerson()) return validateBusiness
            if (isValidForAutomaticApproval(analysis, customer)) return validateBusiness

            validateBusiness.addError("Documento não aprovado pelo Combate à Fraude.")
            return validateBusiness
        }

        if (customer.isNaturalPerson()) return validateBusiness

        if (analysis.items.any { it.document.cpf }) return validateBusiness

        if (!ocrCpfMatchesAccountOwner(analysis, customer)) {
            validateBusiness.addError("O número do documento não pertence a nenhum dos sócios administradores.")
        }

        return validateBusiness
    }

    private Boolean isValidForAutomaticApproval(AccountDocumentAnalysisResponseDTO analysis, Customer customer) {
        if (!analysis.shouldRetryAutomaticApprove) return false

        AutomaticIdentificationReportResponseDTO automaticIdentificationReport = analysis.automaticIdentificationReport

        if (isNaturalPersonValidForAutomaticApproval(automaticIdentificationReport, customer)) return true
        if (isLegalPersonValidForAutomaticApproval(automaticIdentificationReport, customer)) return true

        return false
    }

    private Boolean isNaturalPersonValidForAutomaticApproval(AutomaticIdentificationReportResponseDTO automaticIdentificationReport, Customer customer) {
        if (!customer.isNaturalPerson()) return false
        if (!customer.birthDate || !customer.name) return false

        String ocrName = getOcrName(automaticIdentificationReport)
        String ocrBirthDate = getOcrBirthDate(automaticIdentificationReport)

        return nameAndBirthDateMatchesOcr(customer.name, CustomDateUtils.fromDate(customer.birthDate), ocrName, ocrBirthDate)
    }

    private Boolean isLegalPersonValidForAutomaticApproval(AutomaticIdentificationReportResponseDTO automaticIdentificationReport, Customer customer) {
        if (!customer.isLegalPerson()) return false

        String ocrName = getOcrName(automaticIdentificationReport)
        String ocrBirthDate = getOcrBirthDate(automaticIdentificationReport)
        if (!ocrName || !ocrBirthDate) return false

        String partnerCpf = CompanyPartnerQuery.query([column: "cpf", companyCnpj: customer.cpfCnpj, name: ocrName, isAdmin: true]).get()
        if (!partnerCpf) return false

        RevenueServiceRegister revenuePartner = revenueServiceRegisterService.findNaturalPerson(partnerCpf, CustomDateUtils.fromString(ocrBirthDate), RevenueServiceRegisterCacheLevel.MODERATE)
        if (!revenuePartner) return false
        if (!revenuePartner.birthDate) return false

        return nameAndBirthDateMatchesOcr(revenuePartner.name, CustomDateUtils.fromDate(revenuePartner.birthDate), ocrName, ocrBirthDate)
    }

    private Boolean nameAndBirthDateMatchesOcr(String name, String birthDate, String ocrName, String ocrBirthDate) {
        if (!name || !birthDate) return false
        if (!ocrName || !ocrBirthDate) return false

        if (ocrName.toUpperCase() != name.toUpperCase()) return false
        if (ocrBirthDate != birthDate) return false

        return true
    }

    private Boolean documentStatusIsAwaitingApproval(Customer customer) {
        return customer.customerRegisterStatus.documentStatus == Status.AWAITING_APPROVAL
    }

    private Boolean accountDocumentIsPending(AccountDocumentAnalysisResponseDTO analysis) {
        List<Long> documentIdList = analysis.items.collect { it.document.id }

        for (Long documentId in documentIdList) {
            CustomerDocument customerDocument = CustomerDocument.read(documentId)
            if (!customerDocument) return false
            if (!customerDocument.status.isPending()) return false
        }
        return true
    }

    private Boolean ocrCpfMatchesAccountOwner(AccountDocumentAnalysisResponseDTO analysis, Customer customer) {
        String cpf = getOcrCpf(analysis.automaticIdentificationReport)
        if (!cpf) return false

        return CompanyPartnerQuery.query([exists: true, companyCnpj: customer.cpfCnpj, cpf: cpf, isAdmin: true]).get().asBoolean()
    }

    private String getOcrCpf(AutomaticIdentificationReportResponseDTO automaticIdentificationReport) {
        if (automaticIdentificationReport.isRg()) return Utils.removeNonNumeric(automaticIdentificationReport.ocrRg.cpf)
        if (automaticIdentificationReport.isCNH()) return Utils.removeNonNumeric(automaticIdentificationReport.ocrCnh.cpf)
        if (automaticIdentificationReport.isCtps()) return Utils.removeNonNumeric(automaticIdentificationReport.ocrCtps.cpf)
        if (automaticIdentificationReport.isOther()) return Utils.removeNonNumeric(automaticIdentificationReport.ocrOther.cpf)
        return null
    }

    private String getOcrBirthDate(AutomaticIdentificationReportResponseDTO automaticIdentificationReport) {
        if (automaticIdentificationReport.isRg()) return automaticIdentificationReport.ocrRg.birthDate
        if (automaticIdentificationReport.isCNH()) return automaticIdentificationReport.ocrCnh.birthDate
        if (automaticIdentificationReport.isPassport()) return automaticIdentificationReport.ocrPassport.birthDate
        if (automaticIdentificationReport.isCtps()) return automaticIdentificationReport.ocrCtps.birthDate
        if (automaticIdentificationReport.isOther()) return automaticIdentificationReport.ocrOther.birthDate
        return null
    }

    private String getOcrName(AutomaticIdentificationReportResponseDTO automaticIdentificationReport) {
        if (automaticIdentificationReport.isRg()) return automaticIdentificationReport.ocrRg.name
        if (automaticIdentificationReport.isCNH()) return automaticIdentificationReport.ocrCnh.name
        if (automaticIdentificationReport.isPassport()) return automaticIdentificationReport.ocrPassport.name
        if (automaticIdentificationReport.isCtps()) return automaticIdentificationReport.ocrCtps.name
        if (automaticIdentificationReport.isOther()) return automaticIdentificationReport.ocrOther.name
        return null
    }

    private DocumentAnalysisRejectReason getDocumentAnalysisRejectReason(AccountDocumentAnalysisResponseDTO analysisResponseDTO) {
        DocumentAnalysisRejectReason rejectReason = DocumentAnalysisRejectReason.convert(analysisResponseDTO.rejectReason)
        return rejectReason
    }

    private CustomerGeneralAnalysisRejectReason getGeneralAnalysisRejectReason(AccountDocumentAnalysisResponseDTO analysisResponseDTO) {
        CustomerGeneralAnalysisRejectReason rejectReason = CustomerGeneralAnalysisRejectReason.convert(analysisResponseDTO.accountRejectReason)
        return rejectReason
    }

    private Map getCustomerDocuments(AccountDocumentAnalysisResponseDTO analysis) {
        List<DocumentDTO> documentDTOList = analysis.items.collect { AccountDocumentAnalysisItemResponseDTO it -> it.document }
        List<CustomerDocument> customerDocumentList = []
        List<CustomerDocumentFile> customerDocumentFileList = []

        for (DocumentDTO documentDTO in documentDTOList) {
            for (DocumentFileDTO documentFileDTO in documentDTO.files) {
                CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.get(documentFileDTO.id)

                if (!customerDocumentFile) {
                    AsaasLogger.warn("AccountDocumentAnalysisApprovalService.getCustomerDocuments >> CustomerDocumentFile não encontrado. Id: [${documentFileDTO.id}].")
                    continue
                }
                if (!customerDocumentFile.deleted) {
                    customerDocumentFileList.add(customerDocumentFile)

                    CustomerDocument customerDocument = customerDocumentFile.customerDocument

                    if (!customerDocumentList.any { it.id == customerDocument.id }) {
                        customerDocumentList.add(customerDocument)
                    }
                }
            }
        }

        return [customerDocumentList: customerDocumentList, customerDocumentFileList: customerDocumentFileList]
    }

    private Boolean hasOtherNotApprovedDocument(List<CustomerDocument> customerDocumentList, Customer customer) {
        if (!documentStatusIsAwaitingApproval(customer)) return true

        List<Long> documentIdList = customerDocumentList.collect { it.id }
        List<Long> customerDocumentGroupIdList = CustomerDocumentGroup.query([column: "id", customer: customer]).list() as List<Long>

        Map searchParams = [
            exists: true,
            customer: customer,
            "groupId[in]": customerDocumentGroupIdList,
            "id[notIn]": documentIdList,
            "type[in]": CustomerDocumentType.getValidValues(),
            "status[ne]": CustomerDocumentStatus.APPROVED
        ]

        Boolean hasCustomerDocumentNotApproved = CustomerDocument.query(searchParams).get().asBoolean()

        return hasCustomerDocumentNotApproved
    }

    private Map buildAnalysisData(AccountDocumentAnalysisResponseDTO analysis) {
        Map analysisData = [
            ocrCpf: getOcrCpf(analysis.automaticIdentificationReport),
            ocrBirthDate: getOcrBirthDate(analysis.automaticIdentificationReport)
        ]

        return analysisData
    }
}
