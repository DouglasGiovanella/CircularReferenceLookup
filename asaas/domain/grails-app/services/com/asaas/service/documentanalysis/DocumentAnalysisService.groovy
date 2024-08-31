package com.asaas.service.documentanalysis

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.documentanalysis.DocumentAnalysisPostponedReason
import com.asaas.documentanalysis.DocumentAnalysisRejectReason
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.documentanalysis.DocumentRejectType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.CustomerDocumentFile
import com.asaas.domain.customerdocumentanalysisstatus.CustomerDocumentAnalysisStatus
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.documentanalysis.DocumentAnalysisItem
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.status.Status
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class DocumentAnalysisService {

    def analysisInteractionService
    def asaasSegmentioService
    def authorizationDeviceUpdateRequestService
    def customerAlertNotificationService
    def customerDocumentAnalysisStatusService
    def customerDocumentService
    def customerDocumentFileService
    def customerGeneralAnalysisService
    def customerRegisterStatusService
    def customerMessageService
    def identificationDocumentDoubleCheckAnalysisService
    def mobilePushNotificationService
    def treasureDataService
    def userAdditionalInfoService

    public DocumentAnalysis createAnalysis(Customer customer, User analyst) {
        DocumentAnalysis documentAnalysis = DocumentAnalysis.notFinished([customer: customer]).get()
        CustomerDocumentAnalysisStatus customerDocumentAnalysisStatus = CustomerDocumentAnalysisStatus.query([customerId: customer.id]).get()

        BusinessValidation canStartsAnalysisValidation = canStartAnalysis(documentAnalysis, customerDocumentAnalysisStatus, analyst)
        if (!canStartsAnalysisValidation.isValid()) {
            String message = canStartsAnalysisValidation.getFirstErrorMessage()
            AsaasLogger.warn("DocumentAnalysisService.createAnalysis >> ${message}")
            throw new BusinessException(message)
        }

        Boolean isNewAnalysis = false
        if (!documentAnalysis) {
            documentAnalysis = new DocumentAnalysis()
            documentAnalysis.customer = customer
            documentAnalysis.user = analyst
            isNewAnalysis = true
        }

        documentAnalysis.status = DocumentAnalysisStatus.IN_PROGRESS
        documentAnalysis.save(failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)

        if (isNewAnalysis) {
            analysisInteractionService.createForDocumentAnalysis(analyst, AnalysisInteractionType.START, documentAnalysis)
        }

        return documentAnalysis
    }

    public DocumentAnalysis saveManualAnalysisRequired(Customer customer, String observations) {
        DocumentAnalysis documentAnalysis = new DocumentAnalysis()
        documentAnalysis.customer = customer
        documentAnalysis.observations = observations
        documentAnalysis.status = DocumentAnalysisStatus.MANUAL_ANALYSIS_REQUIRED
        documentAnalysis.save(failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)

        return documentAnalysis
    }

    public DocumentAnalysis saveManually(Long documentAnalysisId, Customer customer, DocumentAnalysisStatus status, String observations, DocumentAnalysisRejectReason rejectReason, List<Map> analysisResultList) {
        validateSaveManually(customer, status, analysisResultList)

        DocumentAnalysis documentAnalysis = DocumentAnalysis.get(documentAnalysisId)
        documentAnalysis.status = status
        documentAnalysis.observations = observations
        documentAnalysis.rejectReason = rejectReason
        documentAnalysis.documentRejectType = DocumentRejectType.MANUAL_ANALYSIS
        documentAnalysis.save(flush: true, failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)

        for (Map analysisResultItem : analysisResultList) {
            analysisResultItem.status = CustomerDocumentStatus.valueOf(analysisResultItem.status)
            if (analysisResultItem.object == CustomerDocument.class.simpleName) {
                processCustomerDocumentAnalysisResult(documentAnalysis, analysisResultItem)
            } else if (analysisResultItem.object == CustomerDocumentFile.class.simpleName) {
                processCustomerDocumentFileAnalysisResult(documentAnalysis, analysisResultItem)
            }
        }

        DocumentAnalysis finishedDocumentAnalysis = finishAnalysis(documentAnalysis.id)

        if (finishedDocumentAnalysis.status.isApproved()) {
            userAdditionalInfoService.updateUserBasedOnCustomerIfPossible(customer)
        }

        return finishedDocumentAnalysis
    }

    public DocumentAnalysis approveAccountDocumentAutomatically(Customer customer, List<Long> customerDocumentIdList, List<Long> customerDocumentFileIdList, Map analysisData, DocumentAnalysisStatus documentAnalysisStatus) {
        DocumentAnalysis documentAnalysis = new DocumentAnalysis()
        documentAnalysis.customer = customer
        documentAnalysis.status = documentAnalysisStatus

        if (documentAnalysisStatus.isApproved()) {
            documentAnalysis.observations = Utils.getMessageProperty("system.automaticApproval.description")
        } else {
            documentAnalysis.observations = "Não foi possível aprovar automaticamente, pois há outro(s) documento(s) para analisar."
        }

        documentAnalysis.save(flush: true, failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)

        saveCustomerDocumentList(documentAnalysis, customerDocumentIdList)
        saveCustomerDocumentFileList(documentAnalysis, customerDocumentFileIdList)

        ignoreAccountDocumentFilesNotAnalysed(customer, customerDocumentIdList, customerDocumentFileIdList)

        DocumentAnalysis finishedDocumentAnalysis = finishAnalysis(documentAnalysis.id)
        userAdditionalInfoService.updateUserBasedOnCustomerIfPossible(customer, analysisData)
        sendToDoubleCheckAnalysisIfNecessary(finishedDocumentAnalysis)

        return finishedDocumentAnalysis
    }

    public void rejectDocumentsAutomaticallyByRejectReason(Customer customer, User user, DocumentAnalysisRejectReason rejectReason, List<Long> customerDocumentIdList, List<Long> customerDocumentFileIdList) {
        BusinessValidation businessValidation = canRejectDocumentAutomatically(customer)
        if (!businessValidation.isValid()) return

        String observation = Utils.getMessageProperty("DocumentAnalysisRejectReason.attribute.${rejectReason}")

        DocumentAnalysis documentAnalysis = new DocumentAnalysis()
        documentAnalysis.customer = customer
        documentAnalysis.user = user
        documentAnalysis.status = DocumentAnalysisStatus.REJECTED
        documentAnalysis.observations = observation
        documentAnalysis.rejectReason = DocumentAnalysisRejectReason.convert(rejectReason)
        documentAnalysis.documentRejectType = DocumentRejectType.AUTOMATIC_ANALYSIS

        documentAnalysis.save(flush: true, failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)

        processDocumentRejectionAutomatically(documentAnalysis, customerDocumentIdList, customerDocumentFileIdList)
    }

    public List<DocumentAnalysis> list(Customer customer) {
        return DocumentAnalysis.query([customer: customer]).list()
    }

    public DocumentAnalysis findLastDocumentAnalysis(Customer customer) {
        return DocumentAnalysis.query([customer: customer, sort: "id", order: "desc"]).get()
    }

    public void deleteNotFinishedAnalysisInBatch() {
        Calendar twoHoursAgo = Calendar.getInstance()
        twoHoursAgo.add(Calendar.HOUR_OF_DAY, -2)

        List<DocumentAnalysis> documentAnalysisList = DocumentAnalysis.notFinished(["lastUpdated[le]": twoHoursAgo.getTime()]).list()

        for (DocumentAnalysis documentAnalysis : documentAnalysisList) {
            documentAnalysis.deleted = true
            documentAnalysis.save(failOnError: true)

            customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(documentAnalysis.customer, DocumentAnalysisStatus.MANUAL_ANALYSIS_REQUIRED)

            AsaasLogger.warn("DocumentAnalysisService.deleteNotFinishedAnalysisInBatch >> A análise de documentos do cliente [${documentAnalysis.customer.id}] que estava em andamento pelo analista [${documentAnalysis.user.username}] será removida pois encontra-se em andamento há mais de duas horas.")
        }
    }

    public void postponeAnalysis(Customer customer, DocumentAnalysisPostponedReason reason, String reasonDescription, User currentUser) {
        if (!reason) throw new BusinessException("Por favor, selecione um motivo para a prorrogação de análise.")

        DocumentAnalysis documentAnalysis = DocumentAnalysis.query([customer: customer, user: currentUser, status: DocumentAnalysisStatus.IN_PROGRESS]).get()

        if (!documentAnalysis) throw new BusinessException("Nenhuma análise de documentos do cliente [${customer.id}] encontrada para o analista [${currentUser.id}].")

        documentAnalysis.status = DocumentAnalysisStatus.POSTPONED
        String postponedReason =  Utils.getMessageProperty("DocumentAnalysisPostponedReason.text." + reason)
        documentAnalysis.observations = "Motivo: ${postponedReason}."
        documentAnalysis.postponedReason = reason

        if (reasonDescription) documentAnalysis.observations += System.lineSeparator() + reasonDescription

        documentAnalysis.save(failOnError: true)

        customerDocumentAnalysisStatusService.updateDocumentAnalysisStatus(customer, documentAnalysis.status)
    }

    public Long findInProgressAnalysisId(Customer customer, User analyst) {
        return DocumentAnalysis.query([column: "id", customer: customer, user: analyst, status: DocumentAnalysisStatus.IN_PROGRESS]).get()
    }

    private DocumentAnalysis finishAnalysis(Long documentAnalysisId) {
        DocumentAnalysis documentAnalysis = DocumentAnalysis.get(documentAnalysisId)

        for (DocumentAnalysisItem documentAnalysisItem : documentAnalysis.documentAnalysisItems) {
            if (documentAnalysisItem.status.isApproved() || documentAnalysis.status.isAccountDocumentAnalysisApproved()) {
                if (documentAnalysisItem.customerDocument) {
                    customerDocumentService.approve(documentAnalysisItem.customerDocument)
                } else if (documentAnalysisItem.customerDocumentFile) {
                    documentAnalysisItem.customerDocumentFile.status = CustomerDocumentStatus.APPROVED
                    documentAnalysisItem.customerDocumentFile.save(flush: true, failOnError: true)
                }
            } else if (documentAnalysisItem.status.isRejected()) {
                if (documentAnalysisItem.customerDocument) {
                    customerDocumentService.reject(documentAnalysisItem.customerDocument)
                } else if (documentAnalysisItem.customerDocumentFile) {
                    documentAnalysisItem.customerDocumentFile.status = CustomerDocumentStatus.REJECTED
                    documentAnalysisItem.customerDocumentFile.save(flush: true, failOnError: true)
                }
            }
        }

        if (!documentAnalysis.status.isAccountDocumentAnalysisApproved()) {
            trackAnalysisResult(documentAnalysis)
            customerRegisterStatusService.updateDocumentStatus(documentAnalysis.customer, Status.valueOf(documentAnalysis.status.toString()))
            notifyCustomerAboutDocumentAnalysisResult(documentAnalysis)
        }

        if (documentAnalysis.status.isApproved()) {
            customerGeneralAnalysisService.approveAutomaticallyIfPossible(documentAnalysis.customer)
            authorizationDeviceUpdateRequestService.processAuthorizationDeviceUpdateRequestIfNecessary(documentAnalysis.customer)
        }

        analysisInteractionService.createForDocumentAnalysis(documentAnalysis.user, AnalysisInteractionType.FINISH, documentAnalysis)

        return documentAnalysis
    }

    private void validateSaveManually(Customer customer, DocumentAnalysisStatus status, List<Map> analysisResultList) {
        if (hasRejectedStatusWithoutRejectedDocumentOrNewDocumentRequested(customer, status, analysisResultList)) {
            throw new BusinessException("Por favor, selecione pelo menos um documento a ser reprovado ou solicite um documento extra.")
        }

        if (hasInvalidExpirationDate(analysisResultList)) {
            throw new BusinessException("Data de validade digitada inválida.")
        }

        CustomerDocumentAnalysisStatus customerDocumentAnalysisStatus = CustomerDocumentAnalysisStatus.query([customerId: customer.id]).get()
        if (customerDocumentAnalysisStatus && !customerDocumentAnalysisStatus.status.isInProgress()) {
            throw new BusinessException("Não foi possível finalizar esta análise, pois a documentação do cliente sofreu alterações durante o processo.")
        }
    }

    private Boolean hasInvalidExpirationDate(List<Map> documentAnalysisResultList) {
        for (Map analysis : documentAnalysisResultList) {
            if (analysis.object == CustomerDocument.class.simpleName && analysis.expirationDate) {
                if (!CustomDateUtils.fromString(analysis.expirationDate)) return true
            }
        }

        return false
    }

    private Boolean hasRejectedStatusWithoutRejectedDocumentOrNewDocumentRequested(Customer customer, DocumentAnalysisStatus analysisResultStatus, List<Map> documentAnalysisResultList) {
        if (!analysisResultStatus.isRejected()) return false

        Boolean hasDocumentRequestedNotSent = CustomerDocument.query([customer: customer, type: CustomerDocumentType.CUSTOM, status: CustomerDocumentStatus.NOT_SENT, exists: true]).get().asBoolean()
        if (hasDocumentRequestedNotSent) return false

        Boolean hasRejectedDocument = documentAnalysisResultList.any { Map analysis -> analysis.status == DocumentAnalysisStatus.REJECTED.toString() }
        return !hasRejectedDocument
    }

    private void saveCustomerDocumentList(DocumentAnalysis documentAnalysis, List<Long> customerDocumentIdList) {
        for (Long customerDocumentId : customerDocumentIdList) {
            CustomerDocument customerDocument = CustomerDocument.read(customerDocumentId)

            DocumentAnalysisItem documentAnalysisItem = new DocumentAnalysisItem()
            documentAnalysisItem.documentAnalysis = documentAnalysis
            documentAnalysisItem.customerDocument = customerDocument
            documentAnalysisItem.status = documentAnalysis.status
            documentAnalysisItem.save(flush: true, failOnError: true)

            documentAnalysis.addToDocumentAnalysisItems(documentAnalysisItem)
        }
    }

    private void saveCustomerDocumentFileList(DocumentAnalysis documentAnalysis, List<Long> customerDocumentFileIdList) {
        for (Long customerDocumentFileId : customerDocumentFileIdList) {
            CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.read(customerDocumentFileId)

            DocumentAnalysisItem documentAnalysisItem = new DocumentAnalysisItem()
            documentAnalysisItem.documentAnalysis = documentAnalysis
            documentAnalysisItem.customerDocumentFile = customerDocumentFile
            documentAnalysisItem.status = documentAnalysis.status
            documentAnalysisItem.save(flush: true, failOnError: true)

            documentAnalysis.addToDocumentAnalysisItems(documentAnalysisItem)
        }
    }

    private void notifyCustomerAboutDocumentAnalysisResult(DocumentAnalysis documentAnalysis) {
        if (!shouldNotifyCustomerAboutDocumentAnalysis(documentAnalysis)) return

        customerAlertNotificationService.notifyDocumentAnalysisResult(documentAnalysis)

        customerMessageService.notifyAboutDocumentAnalysis(documentAnalysis.customer, documentAnalysis)

        if (documentAnalysis.status.isApproved()) return

        mobilePushNotificationService.notifyDocumentAnalysisResult(documentAnalysis)
    }


    private void processCustomerDocumentAnalysisResult(DocumentAnalysis documentAnalysis, Map analysisResultItem) {
        CustomerDocument customerDocument
        if (analysisResultItem.objectId) {
            customerDocument = CustomerDocument.get(Long.valueOf(analysisResultItem.objectId))
        } else {
            customerDocument = customerDocumentService.save(
                documentAnalysis.customer,
                null,
                CustomerDocumentGroupType.convert(analysisResultItem.groupType),
                CustomerDocumentType.convert(analysisResultItem.type),
                []
            )
        }

        DocumentAnalysisItem analysisItem = new DocumentAnalysisItem(documentAnalysis: documentAnalysis, customerDocument: customerDocument)

        if (analysisResultItem.status == CustomerDocumentStatus.APPROVED) {
            analysisItem.status = DocumentAnalysisStatus.APPROVED
            if (analysisResultItem.expirationDate) {
                analysisItem.customerDocument.expirationDate = CustomDateUtils.fromString(analysisResultItem.expirationDate)
            }
            analysisItem.save(flush: true, failOnError: true)

            ignoreRejectedCustomerDocumentFiles(customerDocument)
        } else if (analysisResultItem.status == CustomerDocumentStatus.REJECTED) {
            analysisItem.status = DocumentAnalysisStatus.REJECTED
            analysisItem.save(flush: true, failOnError: true)
        }

        analysisItem.save(flush: true, failOnError: true)
        documentAnalysis.addToDocumentAnalysisItems(analysisItem)
    }

    private void processCustomerDocumentFileAnalysisResult(DocumentAnalysis documentAnalysis, Map analysisResultItem) {
        CustomerDocumentFile customerDocumentFile = CustomerDocumentFile.findById(Long.valueOf(analysisResultItem.objectId))

        DocumentAnalysisItem analysisItem = new DocumentAnalysisItem(documentAnalysis: documentAnalysis, customerDocumentFile: customerDocumentFile)

        if (analysisResultItem.status == CustomerDocumentStatus.APPROVED) {
            analysisItem.status = DocumentAnalysisStatus.APPROVED

            analysisItem.save(flush: true, failOnError: true)

        } else if (analysisResultItem.status == CustomerDocumentStatus.REJECTED) {
            analysisItem.status = DocumentAnalysisStatus.REJECTED

            analysisItem.save(flush: true, failOnError: true)
        }

        documentAnalysis.addToDocumentAnalysisItems(analysisItem)
    }

    private BusinessValidation canRejectDocumentAutomatically(Customer customer) {
        BusinessValidation validateBusiness = new BusinessValidation()

        if (customer.customerRegisterStatus.documentStatus == Status.PENDING) {
            validateBusiness.addError("Documentação com status pendente (CustomerRegisterStatus.documentStatus).")
            return validateBusiness
        }

        Boolean lastAnalysisWasManual = DocumentAnalysis.query([customer: customer, column: 'documentRejectType']).get() == DocumentRejectType.MANUAL_ANALYSIS
        if (customer.customerRegisterStatus.documentStatus == Status.REJECTED && lastAnalysisWasManual) {
            validateBusiness.addError("Documentação com status reprovado (CustomerRegisterStatus.documentStatus) por análise manual.")
            return validateBusiness
        }

        return validateBusiness
    }

    private void processDocumentRejectionAutomatically(DocumentAnalysis documentAnalysis, List<Long> customerDocumentIdList, List<Long> customerDocumentFileIdList) {
        saveCustomerDocumentList(documentAnalysis, customerDocumentIdList)
        saveCustomerDocumentFileList(documentAnalysis, customerDocumentFileIdList)

        if (documentAnalysis.documentRejectType?.isAutomaticAnalysis()) {
            for (Long customerDocumentId : customerDocumentIdList) {
                Map analysisResultItem = [:]
                analysisResultItem.objectId = customerDocumentId
                analysisResultItem.type = CustomerDocumentType.IDENTIFICATION.toString()
                analysisResultItem.status = CustomerDocumentStatus.REJECTED

                processCustomerDocumentAnalysisResult(documentAnalysis, analysisResultItem)
            }
        }

        finishAnalysis(documentAnalysis.id)

        asaasSegmentioService.track(documentAnalysis.customer.id, "Logged :: DocumentAnalysis :: Criado automaticamente a analise de documento rejeitada e cliente notificado", [:])
    }

    private void ignoreRejectedCustomerDocumentFiles(CustomerDocument customerDocument) {
        if (customerDocument.status == CustomerDocumentStatus.APPROVED) {
            List<CustomerDocumentFile> customerDocumentFileList = customerDocumentFileService.list(customerDocument.group.customer, customerDocument.id)

            for (customerDocumentFile in customerDocumentFileList) {
                if (customerDocumentFile.status == CustomerDocumentStatus.REJECTED) {
                    customerDocumentFileService.ignore(customerDocumentFile.id)
                }
            }
        }
    }

    private Boolean shouldNotifyCustomerAboutDocumentAnalysis(DocumentAnalysis documentAnalysis) {

        Customer customer = documentAnalysis.customer

        if (!customer.potentialCustomer) return false

        if (customer.customerRegisterStatus.generalApproval.isRejected()) return false

        if (customer.suspectedOfFraud) return false

        if (customer.getIsBlocked()) return false

        if (documentAnalysis.status == DocumentAnalysisStatus.APPROVED && documentAnalysis.documentRejectType == DocumentRejectType.MANUAL_ANALYSIS) return false

        return true
    }

    private void trackAnalysisResult(DocumentAnalysis documentAnalysis) {
        Map data = [asaasUser: UserUtils.getCurrentUser()?.username]
        asaasSegmentioService.track(documentAnalysis.customer.id, "Logged :: Documentaçao :: Documentos ${documentAnalysis.status.isApproved() ? 'aprovados' : 'reprovados'}", data)
        treasureDataService.track(documentAnalysis.customer, TreasureDataEventType.DOCUMENT_ANALYZED, [documentAnalysisId: documentAnalysis.id])
    }

    private void ignoreAccountDocumentFilesNotAnalysed(Customer customer, List<Long> customerDocumentIdList, List<Long> customerDocumentFileIdList) {
        if (!customerDocumentFileIdList) return

        for (Long customerDocumentId : customerDocumentIdList) {
            List<Long> customerDocumentFileIdNotAnalysed = CustomerDocumentFile.query([column: "id", customer: customer, customerDocumentId: customerDocumentId, "id[notIn]": customerDocumentFileIdList]).list()

            for (Long customerDocumentFileId : customerDocumentFileIdNotAnalysed) {
                customerDocumentFileService.ignore(customerDocumentFileId)
            }
        }
    }

    private void sendToDoubleCheckAnalysisIfNecessary(DocumentAnalysis documentAnalysis) {
        final Integer samplingPercentage = 5

        if (!Utils.isPropertyInPercentageRange(documentAnalysis.customer.id, samplingPercentage)) return
        DocumentAnalysisItem documentAnalysisItem = documentAnalysis.documentAnalysisItems.find {
            it.customerDocument?.type?.isIdentificationSelfie()
        }

        if (!documentAnalysisItem) return
        identificationDocumentDoubleCheckAnalysisService.save(documentAnalysis.customer, documentAnalysisItem.customerDocument)
    }

    private BusinessValidation canStartAnalysis(DocumentAnalysis documentAnalysis, CustomerDocumentAnalysisStatus customerDocumentAnalysisStatus, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!documentAnalysis) return validatedBusiness
        if (!documentAnalysis.user) return validatedBusiness
        if (!customerDocumentAnalysisStatus) return validatedBusiness

        if (customerDocumentAnalysisStatus.status.isInProgress() && documentAnalysis.user.id != analyst.id) {
            validatedBusiness.addError("documentAnalysis.canStartAnalysis.error.alreadyStarted", [documentAnalysis.customer.email, documentAnalysis.user.username])
        }

        return validatedBusiness
    }
}
