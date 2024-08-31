package com.asaas.service.api

import com.asaas.api.ApiAuthorizationDeviceParser
import com.asaas.api.ApiAuthorizationDeviceUpdateRequestParser
import com.asaas.api.ApiMobileUtils
import com.asaas.authorizationdevice.AuthorizationDeviceNotificationVO
import com.asaas.authorizationdevice.AuthorizationDeviceUpdateRequestStatus
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.login.UserKnownDevice
import com.asaas.domain.user.User
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionType
import com.asaas.user.UserUtils
import com.asaas.utils.UserKnownDeviceUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ApiAuthorizationDeviceUpdateRequestService extends ApiBaseService {

    def apiResponseBuilderService
    def authorizationDeviceUpdateRequestService
    def facematchCriticalActionService
    def mobileAppTokenService
    def mobileAppTokenUpdateRequestService
    def userFacematchCriticalActionService

    def findLatest(params) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.findLatest(getProviderInstance(params))

        if (!authorizationDeviceUpdateRequest) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        Map responseMap = ApiAuthorizationDeviceUpdateRequestParser.buildResponseItem(authorizationDeviceUpdateRequest)
        if (authorizationDeviceUpdateRequest.status == AuthorizationDeviceUpdateRequestStatus.AWAITING_FACEMATCH_CRITICAL_ACTION_AUTHORIZATION) {
            Map facematchMap = facematchCriticalActionService.buildFacematchValidationData(authorizationDeviceUpdateRequest, UserUtils.getCurrentUser(), true)
            responseMap.facematchUrl = facematchMap.url
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    def save(params) {
        Map fields = ApiAuthorizationDeviceUpdateRequestParser.parseRequestParams(params)
        Customer customer = getProviderInstance(params)

        TemporaryFile temporaryFile = TemporaryFile.findByPublicId(fields.temporaryFileId)

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequestService.save(customer, fields.authorizationDeviceId, temporaryFile.id, fields.sendToManualAnalysis)
        if (authorizationDeviceUpdateRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(authorizationDeviceUpdateRequest, [originalErrorCode: true])
        }

        Map responseMap = ApiAuthorizationDeviceUpdateRequestParser.buildResponseItem(authorizationDeviceUpdateRequest)

        if (authorizationDeviceUpdateRequest.authorizationDevice.type.isMobileAppToken()) {
            responseMap.authorizationDevice.secretKey = mobileAppTokenService.encryptSecretKey(authorizationDeviceUpdateRequest.authorizationDevice)
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map saveMobileAppTokenAuthorizationDevice(Map params) {
        User currentUser = UserUtils.getCurrentUser()
        UserKnownDevice userKnownDeviceOrigin = UserKnownDeviceUtils.getCurrentDevice(currentUser.id)
        AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO = new AuthorizationDeviceNotificationVO(currentUser, true)
        AuthorizationDevice authorizationDevice = mobileAppTokenUpdateRequestService.saveAuthorizationDevice(currentUser.customer, userKnownDeviceOrigin, params.deviceModelName, authorizationDeviceNotificationVO)

        if (authorizationDevice.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(authorizationDevice)
        }

        return apiResponseBuilderService.buildSuccess(ApiAuthorizationDeviceParser.buildResponseItem(authorizationDevice))
    }

    public Map requestFacematchValidation(Map params) {
        User currentUser = UserUtils.getCurrentUser()
        UserKnownDevice userKnownDeviceOrigin = UserKnownDeviceUtils.getCurrentDevice(currentUser.id)
        AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO = new AuthorizationDeviceNotificationVO(currentUser, true)
        AuthorizationDevice authorizationDevice = mobileAppTokenUpdateRequestService.saveAuthorizationDevice(currentUser.customer, userKnownDeviceOrigin, params.deviceModelName, authorizationDeviceNotificationVO)

        if (authorizationDevice.hasErrors()) {
            throw new ValidationException(null, authorizationDevice.errors)
        }

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequestService.saveAwaitingFacematchCriticalActionAuthorization(currentUser, authorizationDevice.id)
        if (authorizationDeviceUpdateRequest.hasErrors()) {
            throw new ValidationException(null, authorizationDeviceUpdateRequest.errors)
        }

        Map responseMap = ApiAuthorizationDeviceUpdateRequestParser.buildResponseItem(authorizationDeviceUpdateRequest)
        if (authorizationDeviceUpdateRequest.authorizationDevice.type.isMobileAppToken()) {
            responseMap.authorizationDevice.secretKey = mobileAppTokenService.encryptSecretKey(authorizationDeviceUpdateRequest.authorizationDevice)
        }

        Map facematchMap = facematchCriticalActionService.buildFacematchValidationData(authorizationDeviceUpdateRequest, currentUser, true)
        responseMap.facematchUrl = facematchMap.url

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map canUseFacematch() {
        Boolean canUseFacematch

        if (ApiMobileUtils.appSupportsNewThirdPartyFacematchValidation()) {
            canUseFacematch = userFacematchCriticalActionService.canUserCreate(UserUtils.getCurrentUser(), FacematchCriticalActionType.AUTHORIZATION_DEVICE_UPDATE_REQUEST)
        } else {
            canUseFacematch = false
        }

        return apiResponseBuilderService.buildSuccess([canUseFacematch: canUseFacematch])
    }

    public Map cancel(Map params) {
        Long authorizationDeviceUpdateRequestId = Utils.toLong(params.id)
        User user = UserUtils.getCurrentUser()

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest

        Map facematchCriticalActionQueryMap = [
            requester: user,
            authorizationDeviceUpdateRequestId: authorizationDeviceUpdateRequestId,
            type: FacematchCriticalActionType.AUTHORIZATION_DEVICE_UPDATE_REQUEST
        ]

        FacematchCriticalAction facematchCriticalActionInProgress = FacematchCriticalAction.inProgress(facematchCriticalActionQueryMap).get()
        if (facematchCriticalActionInProgress) {
            facematchCriticalActionService.cancel(facematchCriticalActionInProgress)

            authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.query([customer: user.customer, id: authorizationDeviceUpdateRequestId]).get()
        } else {
            authorizationDeviceUpdateRequest = authorizationDeviceUpdateRequestService.cancel(user.customer, authorizationDeviceUpdateRequestId)
        }

        if (authorizationDeviceUpdateRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(authorizationDeviceUpdateRequest)
        }

        return apiResponseBuilderService.buildSuccess(ApiAuthorizationDeviceUpdateRequestParser.buildResponseItem(authorizationDeviceUpdateRequest))
    }

}
