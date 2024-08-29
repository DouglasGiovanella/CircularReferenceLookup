package com.asaas.service.authorizationdevice

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.authorizationdevice.AuthorizationDeviceUpdateRequestRejectReason
import com.asaas.authorizationdevice.AuthorizationDeviceUpdateRequestStatus
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.user.User
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class AuthorizationDeviceUpdateRequestAdminService {

    def analysisInteractionService
    def asaasSegmentioService
    def authorizationDeviceUpdateRequestService

    public AuthorizationDeviceUpdateRequest startAnalysis(Long id, User analyst) {
        Map searchParams = [id: id, status: AuthorizationDeviceUpdateRequestStatus.AWAITING_APPROVAL, withoutFacematchCriticalAction: true]

        AuthorizationDeviceUpdateRequest analysisRequestPending = AuthorizationDeviceUpdateRequest.mobileAppToken(searchParams).get()

        AuthorizationDeviceUpdateRequest validatedAnalysisRequest = validateStartAnalysis(analysisRequestPending, analyst)

        if (validatedAnalysisRequest.hasErrors()) return validatedAnalysisRequest

        analysisRequestPending.analyst = analyst
        analysisRequestPending.status = AuthorizationDeviceUpdateRequestStatus.MANUAL_ANALYSIS_STARTED
        analysisRequestPending.save(failOnError: true)

        analysisInteractionService.createForAuthorizationDeviceUpdateRequest(analyst, AnalysisInteractionType.START, analysisRequestPending)

        return analysisRequestPending
    }

    public BusinessValidation canStartAnalysis(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User analyst) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!analyst) {
            validatedBusiness.addError("authorizationDeviceUpdateRequestAdmin.canStartAnalysis.error.analystNotIdentified")
            return validatedBusiness
        }

        if (!authorizationDeviceUpdateRequest) {
            validatedBusiness.addError("authorizationDeviceUpdateRequestAdmin.canStartAnalysis.error.analysisNotFound")
            return validatedBusiness
        }

        if (authorizationDeviceUpdateRequest.status.isFinished()) {
            validatedBusiness.addError("authorizationDeviceUpdateRequestAdmin.canStartAnalysis.error.alreadyFinished")
            return validatedBusiness
        }

        if (authorizationDeviceUpdateRequest.status.isManualAnalysisStarted()) {
            validatedBusiness.addError("analysisRequestValidator.canStartAnalysis.error.alreadyStarted")
            return validatedBusiness
        }

        if (authorizationDeviceUpdateRequest.analyst && (authorizationDeviceUpdateRequest.analyst.id != analyst.id)) {
            validatedBusiness.addError("authorizationDeviceUpdateRequestAdmin.canStartAnalysis.error.analysisStartedByAnotherAnalyst")
            return validatedBusiness
        }

        return validatedBusiness
    }

    public AuthorizationDeviceUpdateRequest finishAnalysis(Long id, User analyst, AuthorizationDeviceUpdateRequestStatus status, AuthorizationDeviceUpdateRequestRejectReason rejectReason) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.mobileAppToken([id: id, withoutFacematchCriticalAction: true]).get()

        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = validateFinishAnalysis(authorizationDeviceUpdateRequest, analyst, status, rejectReason)

        if (validatedAuthorizationDeviceUpdateRequest.hasErrors()) {
            return validatedAuthorizationDeviceUpdateRequest
        }

        authorizationDeviceUpdateRequest.status = status
        if (status.isRejected()) {
            authorizationDeviceUpdateRequest.rejectReason = rejectReason
            authorizationDeviceUpdateRequest.authorizationDevice.deleted = true
        }

        authorizationDeviceUpdateRequest.save(failOnError: true)
        asaasSegmentioService.track(authorizationDeviceUpdateRequest.customer.id, "authorization_device_update", [action: "manual_analysis", update_request_id: authorizationDeviceUpdateRequest.id, status: status.toString()])

        authorizationDeviceUpdateRequestService.finish(authorizationDeviceUpdateRequest)

        analysisInteractionService.createForAuthorizationDeviceUpdateRequest(analyst, AnalysisInteractionType.FINISH, authorizationDeviceUpdateRequest)

        return authorizationDeviceUpdateRequest
    }


    public void processIdleAnalysis() {
        final Integer maxItemsPerCycle = 50

        Map searchParams = [column: "id", order: "asc", status: AuthorizationDeviceUpdateRequestStatus.MANUAL_ANALYSIS_STARTED, withoutFacematchCriticalAction: true]
        List<Long> analysisIdList = AuthorizationDeviceUpdateRequest.query(searchParams).list(max: maxItemsPerCycle)
        if (!analysisIdList) return

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError( {
                AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.get(analysisId)
                updateStatusToAwaitingApprovalIfNecessary(authorizationDeviceUpdateRequest)
            }, [onError: { Exception exception ->
                AsaasLogger.error("AuthorizationDeviceUpdateRequestAdminService.processIdleAnalysis >> Falha ao expirar análise ID: [${analysisId}]", exception)
            }])
        }
    }

    private AuthorizationDeviceUpdateRequest validateStartAnalysis(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User analyst) {
        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()

        if (!analyst) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Não é possível iniciar uma análise sem identificar o analista")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!authorizationDeviceUpdateRequest) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Não foi possível encontrar uma análise para iniciar")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!authorizationDeviceUpdateRequest.status.isAwaitingApproval()) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Não é possível iniciar uma análise com situação diferente de pendente")
            return validatedAuthorizationDeviceUpdateRequest
        }

        Map searchParams = [exists: true, analystId: analyst.id, status: AuthorizationDeviceUpdateRequestStatus.MANUAL_ANALYSIS_STARTED, withoutFacematchCriticalAction: true]
        Boolean hasManualAnalysisStarted = AuthorizationDeviceUpdateRequest.mobileAppToken(searchParams).get().asBoolean()

        if (hasManualAnalysisStarted) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Analista já possui uma análise iniciada, volte para a fila e finalize a sua análise em aberto antes de iniciar outra")
            return validatedAuthorizationDeviceUpdateRequest
        }

        return validatedAuthorizationDeviceUpdateRequest
    }

    private AuthorizationDeviceUpdateRequest validateFinishAnalysis(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User analyst, AuthorizationDeviceUpdateRequestStatus status, AuthorizationDeviceUpdateRequestRejectReason rejectReason) {
        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()

        if (!analyst) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Não foi possível identificar o analista ao finalizar a análise")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!authorizationDeviceUpdateRequest.status.isManualAnalysisStarted()) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Esta análise não foi iniciada")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (authorizationDeviceUpdateRequest.analyst && (authorizationDeviceUpdateRequest.analyst.id != analyst.id)) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "Não é possível encerrar a análise iniciada por outro analista")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!status) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "É necessário informar uma situação")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!status.isFinished()) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "A situação deve ser aprovar ou reprovar")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (status.isRejected() && !rejectReason) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "É necessário informar o motivo de reprovação")
            return validatedAuthorizationDeviceUpdateRequest
        }

        if (!AuthorizationDevice.active([exists: true, customer: authorizationDeviceUpdateRequest.customer]).get()) {
            DomainUtils.addError(validatedAuthorizationDeviceUpdateRequest, "O cliente não possui dispositivo ativo")
            return validatedAuthorizationDeviceUpdateRequest
        }

        return validatedAuthorizationDeviceUpdateRequest
    }

    private void updateStatusToAwaitingApprovalIfNecessary(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        if (!authorizationDeviceUpdateRequest) return

        if (!authorizationDeviceUpdateRequest.status.isManualAnalysisStarted()) return

        final Integer maxIdleAnalysisTimeInMinutes = 30
        Date idleAnalysisDateLimit = CustomDateUtils.sumMinutes(new Date(), -maxIdleAnalysisTimeInMinutes)

        Date lastStartDate = analysisInteractionService.findLastStartDate(authorizationDeviceUpdateRequest)
        if (!lastStartDate) return

        if (lastStartDate.after(idleAnalysisDateLimit)) return

        authorizationDeviceUpdateRequest.analyst = null
        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.AWAITING_APPROVAL
        authorizationDeviceUpdateRequest.save(failOnError: true)
    }
}
