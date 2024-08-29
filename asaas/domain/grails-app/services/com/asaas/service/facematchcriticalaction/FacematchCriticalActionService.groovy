package com.asaas.service.facematchcriticalaction

import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionStatus
import com.asaas.facematchvalidation.vo.FacematchValidationVO
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FacematchCriticalActionService {

    def asaasSegmentioService
    def authorizationDeviceUpdateRequestService
    def createFacematchCriticalActionAnalysisRequestService
    def heimdallFacematchValidationManagerService
    def loginUnlockRequestResultProcessService
    def securityEventNotificationService
    def userUpdateRequestService

    public void processExternalValidation(Long facematchCriticalActionId, Boolean isApproved) {
        FacematchCriticalAction facematchCriticalAction = FacematchCriticalAction.get(facematchCriticalActionId)
        if (!facematchCriticalAction) {
            AsaasLogger.warn("FacematchCriticalActionService.processExternalValidation >> nao foi possivel encontrar FacematchCriticalAction para processar facematch externo. FacematchCriticalActionId [${facematchCriticalActionId}]")
            return
        }

        Map trackInfo = [:]
        trackInfo.action = "external_result_received"
        trackInfo.facematchCriticalActionId = facematchCriticalAction.id
        asaasSegmentioService.track(facematchCriticalAction.requester.customer.id, "facematch_critical_action", trackInfo)

        if (isApproved) {
            authorize(facematchCriticalAction)
            return
        }

        sendToManualAnalysis(facematchCriticalAction)
    }

    public FacematchValidationVO buildFacematchValidation(Long facematchCriticalActionId, User requester, Boolean isMobile) {
        String url = receiveValidationUrl(facematchCriticalActionId, requester, isMobile)

        FacematchValidationVO facematchValidationVO = new FacematchValidationVO(facematchCriticalActionId, requester, url)
        return facematchValidationVO
    }

    public Map buildFacematchValidationData(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User requester) {
        return buildFacematchValidationData(authorizationDeviceUpdateRequest, requester, false)
    }

    public Map buildFacematchValidationData(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User requester, Boolean isMobile) {
        Map searchParams = [:]
        searchParams.columnList = ["id", "requester"]
        searchParams.requester = requester
        searchParams.authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequest
        searchParams.status = FacematchCriticalActionStatus.PENDING

        Map facematchCriticalActionMap = FacematchCriticalAction.query(searchParams).get()

        if (!facematchCriticalActionMap) return [:]

        Long facematchCriticalActionId = facematchCriticalActionMap.id
        Long customerId = facematchCriticalActionMap.requester.customer.id

        trackValidationUrlRequest(customerId, facematchCriticalActionId)

        String url = receiveValidationUrl(facematchCriticalActionId, facematchCriticalActionMap.requester, isMobile)

        trackValidationUrlReceived(customerId, facematchCriticalActionId)

        Map facematchValidationData = [:]
        facematchValidationData.facematchCriticalActionId = facematchCriticalActionId
        facematchValidationData.requester = facematchCriticalActionMap.requester
        facematchValidationData.url = url

        return facematchValidationData
    }

    public Boolean isFinished(Long id, User requester) {
        Map searchParams = [:]
        searchParams.exists = true
        searchParams."id" = id
        searchParams.requester = requester
        searchParams."status[in]" = FacematchCriticalActionStatus.listFinishedStatuses()

        return FacematchCriticalAction.query(searchParams).get().asBoolean()
    }

    public FacematchCriticalActionStatus findStatus(String publicId, String username) {
        Map searchParams = [:]
        searchParams.column = "status"
        searchParams."publicId" = publicId
        searchParams.requesterUsername = username
        searchParams."ignoreRequester" = true

        FacematchCriticalActionStatus facematchCriticalActionStatus = FacematchCriticalAction.query(searchParams).get()

        return facematchCriticalActionStatus
    }

    public void cancel(FacematchCriticalAction facematchCriticalAction) {
        updateStatusIfPossible(facematchCriticalAction, FacematchCriticalActionStatus.CANCELLED)
    }

    public void reject(FacematchCriticalAction facematchCriticalAction) {
        updateStatusIfPossible(facematchCriticalAction, FacematchCriticalActionStatus.REJECTED)
    }

    public void authorize(FacematchCriticalAction facematchCriticalAction) {
        updateStatusIfPossible(facematchCriticalAction, FacematchCriticalActionStatus.AUTHORIZED)
        securityEventNotificationService.notifyAndSaveHistoryAboutFacematchCriticalActionAuthorized(facematchCriticalAction.requester)
    }

    public void processExpired() {
        final Integer maxItemsPerCycle = 50
        final Integer expirationTimeInHour = -24
        Date expirationDate = CustomDateUtils.sumHours(new Date(), expirationTimeInHour)

        List<Long> facematchCriticalActionIdList = FacematchCriticalAction.query([column           : "id",
                                                                                  ignoreRequester  : true,
                                                                                  "dateCreated[lt]": expirationDate,
                                                                                  status           : FacematchCriticalActionStatus.PENDING,
                                                                                  "type[in]"       : FacematchCriticalActionType.listExpirable()]).list(max: maxItemsPerCycle)
        if (!facematchCriticalActionIdList) return

        for (Long facematchCriticalActionId : facematchCriticalActionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                FacematchCriticalAction facematchCriticalAction = FacematchCriticalAction.get(facematchCriticalActionId)
                updateStatusIfPossible(facematchCriticalAction, FacematchCriticalActionStatus.EXPIRED)
            }, [logErrorMessage: "FacematchCriticalActionService.processExpired >> Falha ao expirar criticalAction ID: [${facematchCriticalActionId}]"])
        }
    }

    private void updateStatusIfPossible(FacematchCriticalAction facematchCriticalAction, FacematchCriticalActionStatus status) {
        if (!facematchCriticalAction) throw new BusinessException("Ação crítica de facematch não encontrada.")
        if (FacematchCriticalActionStatus.listFinishedStatuses().contains(facematchCriticalAction.status)) return

        facematchCriticalAction.status = status
        facematchCriticalAction.save(failOnError: true)

        if (facematchCriticalAction.type.isAuthorizationDeviceUpdateRequest()) {
            if (status.isCancelled() || status.isExpired()) {
                authorizationDeviceUpdateRequestService.onFacematchCriticalActionCancellation(facematchCriticalAction)
            } else if (status.isRejected()) {
                authorizationDeviceUpdateRequestService.onFacematchCriticalActionRejection(facematchCriticalAction)
            } else if (status.isAuthorized()) {
                authorizationDeviceUpdateRequestService.onFacematchCriticalActionAuthorization(facematchCriticalAction.authorizationDeviceUpdateRequest)
            }
            return
        }
        if (facematchCriticalAction.type.isLoginUnlockRequest()) {
            if (status.isAuthorized()) {
                loginUnlockRequestResultProcessService.onFacematchCriticalActionAuthorization(facematchCriticalAction)
            } else if (status.isManualAnalysisRequired()) {
                loginUnlockRequestResultProcessService.onFacematchCriticalActionManualAnalysisRequired(facematchCriticalAction)
            } else if (status.isRejected()) {
                loginUnlockRequestResultProcessService.onFacematchCriticalActionRejection(facematchCriticalAction)
            }
            return
        }
        if (facematchCriticalAction.type.isUserUpdateRequest()) {
            if (status.isAuthorized()) {
                userUpdateRequestService.onFacematchCriticalActionAuthorization(facematchCriticalAction.userUpdateRequest)
            } else if (status.isRejected()) {
                userUpdateRequestService.onFacematchCriticalActionRejection(facematchCriticalAction)
            }
        }
    }

    private void sendToManualAnalysis(FacematchCriticalAction facematchCriticalAction) {
        updateStatusIfPossible(facematchCriticalAction, FacematchCriticalActionStatus.MANUAL_ANALYSIS_REQUIRED)
        createFacematchCriticalActionAnalysisRequestService.save(facematchCriticalAction)
    }

    private String receiveValidationUrl(Long facematchCriticalActionId, User requester, Boolean isMobile) {
        trackValidationUrlRequest(requester.customer.id, facematchCriticalActionId)

        String url = heimdallFacematchValidationManagerService.getFacematchValidationUrl(facematchCriticalActionId, requester.id, isMobile)

        trackValidationUrlReceived(requester.customer.id, facematchCriticalActionId)

        return url
    }

    private void trackValidationUrlRequest(Long customerId, Long facematchCriticalActionId) {
        Map trackInfo = [:]
        trackInfo.action = "facematch_validation_url_request"
        trackInfo.facematchCriticalActionId = facematchCriticalActionId
        asaasSegmentioService.track(customerId, "facematch_critical_action", trackInfo)
    }

    private void trackValidationUrlReceived(Long customerId, Long facematchCriticalActionId) {
        Map trackInfo = [:]
        trackInfo.action = "facematch_validation_url_received"
        trackInfo.facematchCriticalActionId = facematchCriticalActionId
        asaasSegmentioService.track(customerId, "facematch_critical_action", trackInfo)
    }
}
