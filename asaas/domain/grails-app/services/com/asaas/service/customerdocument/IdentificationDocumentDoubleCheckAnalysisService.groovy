package com.asaas.service.customerdocument

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.analysisrequest.AnalysisRequestValidator
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocument.CustomerDocument
import com.asaas.domain.customerdocument.IdentificationDocumentDoubleCheckAnalysis
import com.asaas.domain.user.User
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class IdentificationDocumentDoubleCheckAnalysisService {

    def analysisInteractionService
    def customerGeneralAnalysisService

    public IdentificationDocumentDoubleCheckAnalysis save(Customer customer, CustomerDocument identificationDocument) {
        IdentificationDocumentDoubleCheckAnalysis validateAnalysis = validateSave(identificationDocument)
        if (validateAnalysis.hasErrors()) return validateAnalysis

        IdentificationDocumentDoubleCheckAnalysis identificationDocumentAnalysis = new IdentificationDocumentDoubleCheckAnalysis()
        identificationDocumentAnalysis.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        identificationDocumentAnalysis.customer = customer
        identificationDocumentAnalysis.customerDocument = identificationDocument

        identificationDocumentAnalysis.save(failOnError: true)
        return identificationDocumentAnalysis
    }

    public IdentificationDocumentDoubleCheckAnalysis start(User analyst, Long analysisId) {
        IdentificationDocumentDoubleCheckAnalysis identificationDocumentAnalysis

        if (analysisId) {
            identificationDocumentAnalysis = IdentificationDocumentDoubleCheckAnalysis.get(analysisId)
        } else {
            identificationDocumentAnalysis = IdentificationDocumentDoubleCheckAnalysis.query([order: "asc", sort: "id", status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED]).get()
        }

        IdentificationDocumentDoubleCheckAnalysis validatedAnalysisRequest = validateStart(analyst, identificationDocumentAnalysis)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        identificationDocumentAnalysis.analyst = analyst
        identificationDocumentAnalysis.status = AnalysisRequestStatus.STARTED
        identificationDocumentAnalysis.save(failOnError: true)

        analysisInteractionService.createForIdentificationDocumentDoubleCheckAnalysis(analyst, AnalysisInteractionType.START, identificationDocumentAnalysis)

        return identificationDocumentAnalysis
    }

    public IdentificationDocumentDoubleCheckAnalysis finish(User analyst, Map params) {
        Map parsedParams = parseParams(params)
        parsedParams.observations = buildObservationsIfNecessary(parsedParams.status, parsedParams.observations)

        IdentificationDocumentDoubleCheckAnalysis analysis = IdentificationDocumentDoubleCheckAnalysis.get(parsedParams.id)
        IdentificationDocumentDoubleCheckAnalysis validatedAnalysis = validateFinish(analysis, analyst, parsedParams)
        if (validatedAnalysis.hasErrors()) return validatedAnalysis

        analysis.analyst = analyst
        analysis.analysisDate = new Date()
        analysis.analyzed = true
        analysis.status = parsedParams.status
        analysis.observations = parsedParams.observations

        analysis.save(failOnError: true)

        onFinishAnalysis(analysis)

        analysisInteractionService.createForIdentificationDocumentDoubleCheckAnalysis(analyst, AnalysisInteractionType.FINISH, analysis)

        return analysis
    }

    public BusinessValidation canStartAnalysis(User analyst, IdentificationDocumentDoubleCheckAnalysis analysis) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.canStartAnalysis(analysis, analyst)) {
            validatedBusiness.addErrors(analysisRequestValidator.errors)
        }

        return validatedBusiness
    }

    public Boolean canAnalyze(User analyst, IdentificationDocumentDoubleCheckAnalysis analysis) {
        return analysis.analyst == analyst && analysis.status == AnalysisRequestStatus.STARTED
    }

    public void updateStatusToManualAnalysisRequired(IdentificationDocumentDoubleCheckAnalysis analysis) {
        if (!analysis) return
        if (!analysis.status.isStarted()) return

        analysis.analyst = null
        analysis.analysisDate = null
        analysis.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        analysis.save(failOnError: true)
    }

    private IdentificationDocumentDoubleCheckAnalysis validateSave(CustomerDocument identificationDocument) {
        IdentificationDocumentDoubleCheckAnalysis validatedAnalysis = new IdentificationDocumentDoubleCheckAnalysis()

        if (!identificationDocument.type.isIdentificationTypes()) {
            DomainUtils.addError(validatedAnalysis, "Documento não é um document de identificação")
            return validatedAnalysis
        }

        Boolean hasPreviousAnalysis = IdentificationDocumentDoubleCheckAnalysis.query([exists: true, customerDocument: identificationDocument]).get().asBoolean()
        if (hasPreviousAnalysis) {
            DomainUtils.addError(validatedAnalysis, "Documento já possui uma análise criada")
            return validatedAnalysis
        }

        return validatedAnalysis
    }

    private IdentificationDocumentDoubleCheckAnalysis validateStart(User analyst, IdentificationDocumentDoubleCheckAnalysis analysisRequest) {
        IdentificationDocumentDoubleCheckAnalysis validatedAnalysis = new IdentificationDocumentDoubleCheckAnalysis()

        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()
        if (!analysisRequestValidator.validateStart(analyst, analysisRequest)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
            return validatedAnalysis
        }

        IdentificationDocumentDoubleCheckAnalysis pendingAnalysisRequest = IdentificationDocumentDoubleCheckAnalysis
            .query([analyst: analyst, status: AnalysisRequestStatus.STARTED]).get()

        if (pendingAnalysisRequest) {
            DomainUtils.addError(validatedAnalysis, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedAnalysis
        }

        return validatedAnalysis
    }

    private IdentificationDocumentDoubleCheckAnalysis validateFinish(IdentificationDocumentDoubleCheckAnalysis analysis, User analyst, Map analysisMap) {
        IdentificationDocumentDoubleCheckAnalysis validatedAnalysis = new IdentificationDocumentDoubleCheckAnalysis()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.validateFinish(analyst, analysis, analysisMap.status, analysisMap.observations)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
        }

        return validatedAnalysis
    }

    private void onFinishAnalysis(IdentificationDocumentDoubleCheckAnalysis analysis) {
        if (analysis.status.isDenied()) {
            CustomerGeneralAnalysisRejectReason rejectReason = CustomerGeneralAnalysisRejectReason.ADULTERATED_DOCUMENT_IDENTIFICATION
            String observations = "Reprovação realizada através da dupla checagem de documentação de identificação"
            customerGeneralAnalysisService.reject(analysis.customer, rejectReason, observations)
        }
    }

    private Map parseParams(Map params) {
        Map parsedParams = [:]

        parsedParams.id = Long.valueOf(params.id)
        parsedParams.status = AnalysisRequestStatus.convert(params.status)
        parsedParams.observations = params.observations

        return parsedParams
    }

    private String buildObservationsIfNecessary(AnalysisRequestStatus status, String observations) {
        if (!Utils.isEmptyOrNull(observations.trim())) return observations
        if (status.isApproved()) return "Aprovado em ${CustomDateUtils.fromDate(new Date())}"

        return null
    }
}
