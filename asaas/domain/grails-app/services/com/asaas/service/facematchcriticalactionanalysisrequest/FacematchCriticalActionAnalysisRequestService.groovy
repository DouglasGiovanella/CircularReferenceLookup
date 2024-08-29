package com.asaas.service.facematchcriticalactionanalysisrequest

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.analysisrequest.AnalysisRequestValidator
import com.asaas.domain.facematchcriticalaction.FacematchCriticalActionAnalysisRequest
import com.asaas.domain.user.User
import com.asaas.facematchcriticalaction.FacematchCriticalActionAnalysisRequestRejectReason
import com.asaas.facematchvalidation.adapter.FacematchValidationAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class FacematchCriticalActionAnalysisRequestService {

    def analysisInteractionService
    def asaasSegmentioService
    def facematchCriticalActionService
    def heimdallFacematchValidationManagerService

    public FacematchCriticalActionAnalysisRequest start(User analyst, Long analysisId) {
        FacematchCriticalActionAnalysisRequest analysis

        if (analysisId) {
            analysis = FacematchCriticalActionAnalysisRequest.get(analysisId)
        } else {
            analysis = FacematchCriticalActionAnalysisRequest.query([order: "asc", sort: "id", status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED]).get()
        }

        FacematchCriticalActionAnalysisRequest validatedAnalysis = validateStart(analyst, analysis)
        if (validatedAnalysis.hasErrors()) return validatedAnalysis

        analysis.analyst = analyst
        analysis.startAnalysisDate = new Date()
        analysis.status = AnalysisRequestStatus.STARTED
        analysis.save(failOnError: true)

        Map trackInfo = [:]
        trackInfo.analysisId = analysis.id
        trackInfo.action = "start_analysis"
        trackInfo.analyst = analyst.id

        asaasSegmentioService.track(analysis.customer.id, "facematch_analysis", trackInfo)
        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.START, analysis)

        return analysis
    }

    public FacematchCriticalActionAnalysisRequest finish(User analyst, Map params) {
        Map parsedParams = parseParams(params)
        parsedParams.observations = buildObservationsIfNecessary(parsedParams.status, parsedParams.rejectReason, parsedParams.observations)

        FacematchCriticalActionAnalysisRequest analysis = FacematchCriticalActionAnalysisRequest.get(parsedParams.id)
        FacematchCriticalActionAnalysisRequest validatedAnalysis = validateFinish(analysis, analyst, parsedParams)
        if (validatedAnalysis.hasErrors()) return validatedAnalysis

        analysis.analyst = analyst
        analysis.analysisDate = new Date()
        analysis.analyzed = true
        analysis.status = parsedParams.status
        analysis.rejectReason = parsedParams.rejectReason
        analysis.observations = parsedParams.observations

        analysis.save(failOnError: true)

        onFinishAnalysis(analysis)

        analysisInteractionService.createForAnalysisRequest(analyst, AnalysisInteractionType.FINISH, analysis)

        return analysis
    }

    public FacematchValidationAdapter getFacematchValidation(Long facematchCriticalActionId, Long requesterId) {
        FacematchValidationAdapter facematchValidationAdapter = heimdallFacematchValidationManagerService.getFacematchValidation(facematchCriticalActionId, requesterId)
        return facematchValidationAdapter
    }

    public void processIdleAnalysis() {
        final Integer maxItemsPerCycle = 50
        final Integer maxIdleAnalysisTimeInMinutes = -10
        Date idleAnalysisDateLimit = CustomDateUtils.sumMinutes(new Date(), maxIdleAnalysisTimeInMinutes)

        List<Long> analysisIdList = FacematchCriticalActionAnalysisRequest.query([column: "id", order: "asc", "startAnalysisDate[lt]": idleAnalysisDateLimit, status: AnalysisRequestStatus.STARTED]).list(max: maxItemsPerCycle)
        if (!analysisIdList) return

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                FacematchCriticalActionAnalysisRequest analysis = FacematchCriticalActionAnalysisRequest.get(analysisId)
                User analyst = analysis.analyst

                updateStatusToManualAnalysisRequired(analysis)

                if (analysis.status.isManualAnalysisRequired()) {
                    Map trackInfo = [:]
                    trackInfo.analysisId = analysis.id
                    trackInfo.action = "update_to_manual_analysis_required_job"
                    trackInfo.analyst = analyst.id

                    asaasSegmentioService.track(analysis.customer.id, "facematch_analysis", trackInfo)
                }
            }, [logErrorMessage: "FacematchCriticalActionAnalysisRequestService.processIdleAnalysis >> Falha ao expirar análise ID: [${analysisId}]"])
        }
    }

    public void updateStatusToManualAnalysisRequired(FacematchCriticalActionAnalysisRequest analysis) {
        if (!analysis) return
        if (!analysis.status.isStarted()) return

        analysis.analyst = null
        analysis.analysisDate = null
        analysis.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
        analysis.save(failOnError: true)
    }

    public BusinessValidation canStartAnalysis(User analyst, FacematchCriticalActionAnalysisRequest analysis) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.canStartAnalysis(analysis, analyst)) {
            validatedBusiness.addErrors(analysisRequestValidator.errors)
        }

        return validatedBusiness
    }

    public Boolean canAnalyze(User analyst, FacematchCriticalActionAnalysisRequest analysis) {
        return analysis.analyst == analyst && analysis.status == AnalysisRequestStatus.STARTED
    }

    private void onFinishAnalysis(FacematchCriticalActionAnalysisRequest analysis) {
        if (analysis.status.isDenied()) {
            facematchCriticalActionService.reject(analysis.facematchCriticalAction)
        } else if (analysis.status.isApproved()) {
            facematchCriticalActionService.authorize(analysis.facematchCriticalAction)
        }
    }

    private FacematchCriticalActionAnalysisRequest validateStart(User analyst, FacematchCriticalActionAnalysisRequest analysis) {
        FacematchCriticalActionAnalysisRequest validatedAnalysis = new FacematchCriticalActionAnalysisRequest()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.validateStart(analyst, analysis)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
        }

        Boolean hasStartedAnalysis = FacematchCriticalActionAnalysisRequest.query([exists: true, analyst: analyst, status: AnalysisRequestStatus.STARTED]).get().asBoolean()
        if (hasStartedAnalysis) {
            DomainUtils.addError(validatedAnalysis, Utils.getMessageProperty("analysisRequestValidator.validateStart.error.analystAlreadyHasAnotherStartedAnalysis"))
        }

        return validatedAnalysis
    }

    private FacematchCriticalActionAnalysisRequest validateFinish(FacematchCriticalActionAnalysisRequest analysis, User analyst, Map analysisMap) {
        FacematchCriticalActionAnalysisRequest validatedAnalysis = new FacematchCriticalActionAnalysisRequest()
        AnalysisRequestValidator analysisRequestValidator = new AnalysisRequestValidator()

        if (!analysisRequestValidator.validateFinish(analyst, analysis, analysisMap.status, analysisMap.observations)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(analysisRequestValidator, validatedAnalysis)
        }

        if (analysisMap.status?.isDenied() && Utils.isEmptyOrNull(analysisMap.rejectReason)) {
            DomainUtils.addError(validatedAnalysis, "O motivo de reprovação é obrigatório.")
        }

        return validatedAnalysis
    }

    private Map parseParams(Map params) {
        Map parsedParams = [:]

        parsedParams.id = Long.valueOf(params.id)
        parsedParams.status = AnalysisRequestStatus.convert(params.status)
        parsedParams.observations = params.observations
        parsedParams.rejectReason = FacematchCriticalActionAnalysisRequestRejectReason.convert(params.rejectReason)

        return parsedParams
    }

    private String buildObservationsIfNecessary(AnalysisRequestStatus status, FacematchCriticalActionAnalysisRequestRejectReason rejectReason, String observations) {
        if (!Utils.isEmptyOrNull(observations.trim())) return observations
        if (status.isApproved()) return "Aprovado em ${CustomDateUtils.fromDate(new Date())}"
        if (rejectReason && !rejectReason.isOthers()) {
            return Utils.getMessageProperty("FacematchCriticalActionAnalysisRequestRejectReasonDescription.${rejectReason}")
        }

        return null
    }
}
