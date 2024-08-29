package com.asaas.service.authorizationdevice

import com.asaas.asyncaction.AsyncActionType
import com.asaas.authorizationdevice.AuthorizationDeviceNotificationVO
import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.authorizationdevice.AuthorizationDeviceUpdateRequestStatus
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class AuthorizationDeviceUpdateRequestService {

    def asaasSegmentioService
    def asyncActionService
    def authorizationDeviceService
    def createFacematchCriticalActionService
    def customerAlertNotificationService
    def customerDocumentProxyService
    def customerInteractionService
    def customerProofOfLifeMigrationService
    def fileService
    def identificationDocumentAnalysisManagerService
    def mobilePushNotificationService
    def smsTokenService

    public AuthorizationDeviceUpdateRequest save(Customer customer, Long authorizationDeviceId, Long temporaryFileId, Boolean sendToManualAnalysis) {
        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = validateSave(customer)
        if (validatedAuthorizationDeviceUpdateRequest.hasErrors()) {
            return validatedAuthorizationDeviceUpdateRequest
        }

        AuthorizationDevice authorizationDevice = findAuthorizationDeviceAndRestoreIfNecessary(customer, authorizationDeviceId)

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()

        authorizationDeviceUpdateRequest.customer = customer
        authorizationDeviceUpdateRequest.authorizationDevice = authorizationDevice
        authorizationDeviceUpdateRequest.status = sendToManualAnalysis ? AuthorizationDeviceUpdateRequestStatus.AWAITING_APPROVAL : AuthorizationDeviceUpdateRequestStatus.AWAITING_TO_SEND_TO_AUTOMATIC_ANALYSIS
        authorizationDeviceUpdateRequest.file = fileService.saveFileFromTemporary(customer, temporaryFileId)

        authorizationDeviceUpdateRequest.save(failOnError: true)

        sendToIdentificationDocumentAnalysisIfPossible(authorizationDeviceUpdateRequest)

        customerInteractionService.saveAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)

        return authorizationDeviceUpdateRequest
    }

    public AuthorizationDeviceUpdateRequest saveSmsTokenAuthorizationDeviceUpdateRequest(Customer customer, String newPhoneNumber, AuthorizationDeviceNotificationVO authorizationDeviceNotificationVO) {
        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = validateSaveForSmsToken(customer, newPhoneNumber)
        if (validatedAuthorizationDeviceUpdateRequest.hasErrors()) {
            return validatedAuthorizationDeviceUpdateRequest
        }

        AuthorizationDevice authorizationDevice = smsTokenService.savePending(customer, newPhoneNumber, authorizationDeviceNotificationVO)

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()
        authorizationDeviceUpdateRequest.customer = customer
        authorizationDeviceUpdateRequest.authorizationDevice = authorizationDevice
        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.AWAITING_TOKEN_VALIDATION
        authorizationDeviceUpdateRequest.save(failOnError: true)

        customerInteractionService.saveAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)

        return authorizationDeviceUpdateRequest
    }

    public AuthorizationDeviceUpdateRequest saveAwaitingFacematchCriticalActionAuthorization(User requester, Long authorizationDeviceId) {
        AuthorizationDeviceUpdateRequest validatedAuthorizationDeviceUpdateRequest = validateSave(requester.customer)
        if (validatedAuthorizationDeviceUpdateRequest.hasErrors()) {
            return validatedAuthorizationDeviceUpdateRequest
        }

        AuthorizationDevice authorizationDevice = findAuthorizationDeviceAndRestoreIfNecessary(requester.customer, authorizationDeviceId)

        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()
        authorizationDeviceUpdateRequest.customer = requester.customer
        authorizationDeviceUpdateRequest.authorizationDevice = authorizationDevice
        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.AWAITING_FACEMATCH_CRITICAL_ACTION_AUTHORIZATION
        authorizationDeviceUpdateRequest.save(failOnError: true, flush: true)

        createFacematchCriticalActionService.save(authorizationDeviceUpdateRequest, requester)
        customerInteractionService.saveAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)

        return authorizationDeviceUpdateRequest
    }

    public void processAuthorizationDeviceUpdateRequestIfNecessary(Customer customer) {
        List<Long> authorizationDeviceUpdateRequestIds = AuthorizationDeviceUpdateRequest.mobileAppToken([column: "id", sort: "id", order: "desc", customer: customer, status: AuthorizationDeviceUpdateRequestStatus.AWAITING_TO_SEND_TO_AUTOMATIC_ANALYSIS, withoutFacematchCriticalAction: true]).list()
        if (!authorizationDeviceUpdateRequestIds) return

        Long latestAuthorizationDeviceUpdateRequestId = authorizationDeviceUpdateRequestIds.first()
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.get(latestAuthorizationDeviceUpdateRequestId)

        if (authorizationDeviceUpdateRequestIds.size() > 1) {
            authorizationDeviceUpdateRequestIds.remove(latestAuthorizationDeviceUpdateRequestId)

            for (Long authorizationDeviceUpdateRequestId : authorizationDeviceUpdateRequestIds) {
                cancel(customer, authorizationDeviceUpdateRequestId)
            }
        }

        sendToIdentificationDocumentAnalysisIfPossible(authorizationDeviceUpdateRequest)
    }

    public AuthorizationDeviceUpdateRequest cancel(Customer customer, Long authorizationDeviceUpdateRequestId) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.find(customer, authorizationDeviceUpdateRequestId)

        BusinessValidation businessValidation = authorizationDeviceUpdateRequest.canBeCancelled()
        if (!businessValidation.isValid()) {
            DomainUtils.addError(authorizationDeviceUpdateRequest, businessValidation.getFirstErrorMessage())
            return authorizationDeviceUpdateRequest
        }

        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.CANCELLED
        authorizationDeviceUpdateRequest.save(failOnError: true)

        AuthorizationDevice authorizationDevice = authorizationDeviceUpdateRequest.authorizationDevice
        authorizationDevice.deleted = true
        authorizationDevice.save(failOnError: true)

        return authorizationDeviceUpdateRequest
    }


    public void approveByAutomaticAnalysis(Customer customer, Long asaasFileId) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.mobileAppToken([customer: customer, status: AuthorizationDeviceUpdateRequestStatus.AWAITING_APPROVAL, fileId: asaasFileId, withoutFacematchCriticalAction: true]).get()

        if (!authorizationDeviceUpdateRequest) return

        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.APPROVED
        authorizationDeviceUpdateRequest.save(failOnError: true)
        asaasSegmentioService.track(customer.id, "authorization_device_update", [action: "automatic_approval", update_request_id: authorizationDeviceUpdateRequest.id])

        finish(authorizationDeviceUpdateRequest)
    }

    public void onFacematchCriticalActionAuthorization(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.APPROVED
        authorizationDeviceUpdateRequest.save(failOnError: true)
        asaasSegmentioService.track(authorizationDeviceUpdateRequest.customer.id, "authorization_device_update", [action: "facematch_critical_action_approval", update_request_id: authorizationDeviceUpdateRequest.id])

        finish(authorizationDeviceUpdateRequest)
    }

    public AuthorizationDeviceUpdateRequest validateToken(String newDeviceToken, User user) {
        AuthorizationDevice authorizationDevice = smsTokenService.validateToken(user.customer, newDeviceToken)
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.query([authorizationDevice: authorizationDevice]).get()

        if (authorizationDevice.status.isTokenValidated()) {
            createFacematchCriticalAction(authorizationDeviceUpdateRequest, user)
        } else if (authorizationDevice.isMaxActivationAttemptsExceeded()) {
            cancel(user.customer, authorizationDeviceUpdateRequest.id)
        }

        return authorizationDeviceUpdateRequest
    }

    public void onFacematchCriticalActionCancellation(FacematchCriticalAction facematchCriticalAction) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = facematchCriticalAction.authorizationDeviceUpdateRequest

        cancel(authorizationDeviceUpdateRequest.customer, authorizationDeviceUpdateRequest.id)
    }

    public void onFacematchCriticalActionRejection(FacematchCriticalAction facematchCriticalAction) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = facematchCriticalAction.authorizationDeviceUpdateRequest

        cancel(authorizationDeviceUpdateRequest.customer, authorizationDeviceUpdateRequest.id)
    }

    public void resendToken(Long customerId) {
        AuthorizationDevice pendingDevice = AuthorizationDevice.pending([customerId: customerId, type: AuthorizationDeviceType.SMS_TOKEN]).get()
        if (!pendingDevice) {
            throw new BusinessException("Não foi possível encontrar um dispositivo pendente para reenvio de token.")
        }

        if (pendingDevice.isMaxAttemptsToSendToken()) {
            throw new BusinessException("Não foi possível reenviar o token, o limite para envio de token foi excedido.")
        }

        smsTokenService.sendActivationToken(pendingDevice)
    }

    public void finish(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        AuthorizationDevice oldDevice = AuthorizationDevice.active([customer: authorizationDeviceUpdateRequest.customer]).get()

        if (authorizationDeviceUpdateRequest.status.isApproved()) {
            UserDeviceSecurityVO userDeviceSecurityVO = new UserDeviceSecurityVO(null, false)
            authorizationDeviceService.updateCurrentDevice(authorizationDeviceUpdateRequest.customer, authorizationDeviceUpdateRequest.authorizationDevice, userDeviceSecurityVO)
            customerProofOfLifeMigrationService.migrateToSelfieFromAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)
            saveUpdateUserDocumentBasedOnAuthorizationDeviceUpdateRequestAsyncActionIfPossible(authorizationDeviceUpdateRequest)
        }

        notifyAnalysisResult(authorizationDeviceUpdateRequest, oldDevice)
    }

    private AuthorizationDevice findAuthorizationDeviceAndRestoreIfNecessary(Customer customer, Long authorizationDeviceId) {
        AuthorizationDevice authorizationDevice = AuthorizationDevice.query([customer: customer, id: authorizationDeviceId, includeDeleted: true]).get()
        if (!authorizationDevice.deleted) return authorizationDevice

        authorizationDevice.deleted = false
        authorizationDevice.save(failOnError: true)
        return authorizationDevice
    }

    private void createFacematchCriticalAction(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, User user) {
        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.AWAITING_FACEMATCH_CRITICAL_ACTION_AUTHORIZATION
        authorizationDeviceUpdateRequest.save(failOnError: true)

        createFacematchCriticalActionService.save(authorizationDeviceUpdateRequest, user)

        customerInteractionService.saveAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)
    }

    private void notifyAnalysisResult(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest, AuthorizationDevice oldDevice) {
        if (authorizationDeviceUpdateRequest.authorizationDevice.type.isSmsToken()) {
            customerAlertNotificationService.notifyAuthorizationDeviceUpdateRequestSmsTokenResult(authorizationDeviceUpdateRequest)
            mobilePushNotificationService.notifyAuthorizationDeviceUpdateRequestSmsTokenResult(authorizationDeviceUpdateRequest)
        } else {
            customerAlertNotificationService.notifyAuthorizationDeviceUpdateRequestMobileAppTokenResult(authorizationDeviceUpdateRequest, oldDevice)
            mobilePushNotificationService.notifyAuthorizationDeviceUpdateRequestMobileAppTokenResult(authorizationDeviceUpdateRequest, oldDevice)
        }

        customerInteractionService.saveAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)
    }

    private void sendToIdentificationDocumentAnalysisIfPossible(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        CustomerDocumentAdapter customerDocumentAdapter = customerDocumentProxyService.find(authorizationDeviceUpdateRequest.customer.id, [type: CustomerDocumentType.IDENTIFICATION, status: CustomerDocumentStatus.APPROVED])
        if (!customerDocumentAdapter) {
            AsaasLogger.info("[AuthorizationDeviceUpdateRequestService] -> Solicitação de Token sem documentação aprovada, authorizationDeviceUpdateRequest.id: [${authorizationDeviceUpdateRequest.id}], customer: [${authorizationDeviceUpdateRequest.customer.id}]")
            return
        }

        if (!authorizationDeviceUpdateRequest.status.isAwaitingToSendToAutomaticAnalysis()) return

        identificationDocumentAnalysisManagerService.saveAuthorizationDeviceAnalysis(customerDocumentAdapter, authorizationDeviceUpdateRequest.file)

        authorizationDeviceUpdateRequest.status = AuthorizationDeviceUpdateRequestStatus.AWAITING_APPROVAL
        authorizationDeviceUpdateRequest.save(failOnError: true)
    }

    private AuthorizationDeviceUpdateRequest validateSave(Customer customer) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = new AuthorizationDeviceUpdateRequest()

        if (AuthorizationDeviceUpdateRequest.query([customer: customer, exists: true, "status[in]": AuthorizationDeviceUpdateRequestStatus.listAwaitingApproval()]).get().asBoolean()) {
            DomainUtils.addError(authorizationDeviceUpdateRequest, Utils.getMessageProperty("authorizationDeviceUpdateRequest.validate.error.customerAlreadyHasAwaitingApproval"))
        }

        AuthorizationDevice authorizationDevice = AuthorizationDevice.active([customer: customer]).get()
        if (!authorizationDevice) {
            DomainUtils.addErrorWithErrorCode(authorizationDeviceUpdateRequest, "active_device_not_found", Utils.getMessageProperty("authorizationDeviceUpdateRequest.validate.error.activeDeviceNotFound"))
        }

        return authorizationDeviceUpdateRequest
    }

    private AuthorizationDeviceUpdateRequest validateSaveForSmsToken(Customer customer, String phoneNumber) {
        AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = validateSave(customer)

        if (phoneNumber && !PhoneNumberUtils.validateMobilePhone(phoneNumber)) {
            DomainUtils.addError(authorizationDeviceUpdateRequest, Utils.getMessageProperty("authorizationDeviceUpdateRequest.validate.error.invalidPhone"))
        }

        AuthorizationDeviceType authorizationDeviceType = AuthorizationDevice.active([column: "type", customer: customer]).get()
        if (authorizationDeviceType && !authorizationDeviceType.isSmsToken()) {
            DomainUtils.addError(authorizationDeviceUpdateRequest, Utils.getMessageProperty("authorizationDeviceUpdateRequest.validate.error.customerHasNotSmsTypeDevice"))
        }

        AuthorizationDevice pendingDevice = AuthorizationDevice.pending([customer: customer]).get()
        if (pendingDevice) {
            DomainUtils.addError(authorizationDeviceUpdateRequest, Utils.getMessageProperty("authorizationDeviceUpdateRequest.validate.error.customerAlreadyHasAwaitingApproval"))
        }

        return authorizationDeviceUpdateRequest
    }

    private void saveUpdateUserDocumentBasedOnAuthorizationDeviceUpdateRequestAsyncActionIfPossible(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        try {
            if (!authorizationDeviceUpdateRequest.authorizationDevice.type.isMobileAppToken()) return
            if (!authorizationDeviceUpdateRequest.file) return

            Map asyncActionData = [authorizationDeviceUpdateRequestID: authorizationDeviceUpdateRequest.id]
            AsyncActionType asyncActionType = AsyncActionType.UPDATE_USER_DOCUMENT_BASED_ON_AUTHORIZATION_DEVICE_UPDATE_REQUEST
            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

            asyncActionService.save(asyncActionType, asyncActionData)
        } catch (Exception exception) {
            AsaasLogger.error("AuthorizationDeviceUpdateRequestService.saveUpdateUserDocumentBasedOnAuthorizationDeviceUpdateRequestAsyncAction >> AuthorizationDeviceUpdateRequestID [${authorizationDeviceUpdateRequest.id}]", exception)
        }
    }
}
