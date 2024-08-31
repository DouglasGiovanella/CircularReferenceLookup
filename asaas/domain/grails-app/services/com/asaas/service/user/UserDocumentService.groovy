package com.asaas.service.user

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.customerdocument.adapter.CustomerDocumentAdapter
import com.asaas.customerdocument.adapter.CustomerDocumentFileAdapter
import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.file.FileValidator
import com.asaas.log.AsaasLogger
import com.asaas.user.adapter.UserAdditionalInfoAdapter
import com.asaas.user.adapter.UserDocumentAdapter
import com.asaas.user.adapter.UserDocumentFileAdapter
import com.asaas.useradditionalinfo.UserDocumentFileType
import com.asaas.useradditionalinfo.UserDocumentOrigin
import com.asaas.useradditionalinfo.UserDocumentType
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional

@Transactional
class UserDocumentService {

    def asyncActionService
    def customerDocumentFileProxyService
    def customerDocumentGroupProxyService
    def customerDocumentProxyService
    def fileService
    def securityEventNotificationService
    def userAdditionalInfoManagerService
    def userFacematchValidationManagerService
    def userSecurityStageService

    public void send(User user, UserDocumentType userDocumentType, UserDocumentOrigin origin, Map params) {
        Map parsedParams = parseSendParams(params)

        List<AsaasError> asaasErrorList = validateSendParams(parsedParams, userDocumentType)

        if (asaasErrorList) {
            throw new BusinessException(Utils.getMessageProperty(asaasErrorList.first().code, asaasErrorList.first().arguments))
        }

        UserDocumentAdapter userDocumentAdapter = buildUserDocumentFromUserAdditionalInfo(user, userDocumentType, origin, parsedParams.frontFile, parsedParams.backFile)
        userAdditionalInfoManagerService.saveDocumentList(user, [userDocumentAdapter])

        deleteTemporaryFilesAfterSuccessfulSave(parsedParams.frontFile, parsedParams.backFile)
        userSecurityStageService.saveUpdateUserSecurityStageAsyncAction(user.id)

        if (userDocumentType.isIdentificationSelfie()) {
            securityEventNotificationService.notifyAndSaveHistoryAboutSelfieUpload(user)
        }
    }

    public void processUpdateUserDocumentBasedOnAuthorizationDeviceUpdateRequest() {
        final Integer maxItemsPerCycle = 50
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.UPDATE_USER_DOCUMENT_BASED_ON_AUTHORIZATION_DEVICE_UPDATE_REQUEST, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError ({
                AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest = AuthorizationDeviceUpdateRequest.read(asyncActionData.authorizationDeviceUpdateRequestID)
                if (!authorizationDeviceUpdateRequest) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                if (!authorizationDeviceUpdateRequest.authorizationDevice.type.isMobileAppToken()) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                if (!authorizationDeviceUpdateRequest.file) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                sendIdentificationDocumentsFromAuthorizationDeviceUpdateRequestIfNecessary(authorizationDeviceUpdateRequest)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [onError: { Exception exception ->
                AsaasLogger.error("UserDocumentService.processUpdateUserDocumentBasedOnAuthorizationDeviceUpdateRequest >> AsyncActionID [${asyncActionData.asyncActionId}]", exception)
                asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
            }])
        }
    }

    public void sendApprovedIdentificationDocuments(User user) {
        List<UserDocumentAdapter> userDocumentAdapterList = buildIdentificationUserDocumentListFromCustomer(user, true)
        if (!userDocumentAdapterList) return

        userAdditionalInfoManagerService.saveDocumentList(user, userDocumentAdapterList)
    }

    private void sendIdentificationDocumentsFromAuthorizationDeviceUpdateRequestIfNecessary(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        User user = findUserToReceiveDocumentsFromAuthorizationDeviceUpdateRequest(authorizationDeviceUpdateRequest)
        if (!user) {
            AsaasLogger.info("UserDocumentService.sendIdentificationDocumentsFromAuthorizationDeviceUpdateRequestIfNecessary >> não foi possível encontrar um usuário apto para receber o documento do TokenAPP [${authorizationDeviceUpdateRequest.id}]")
            return
        }

        UserAdditionalInfoAdapter userAdditionalInfoAdapter = userAdditionalInfoManagerService.get(user.id)
        if (!userAdditionalInfoAdapter) {
            AsaasLogger.info("UserDocumentService.sendIdentificationDocumentsFromAuthorizationDeviceUpdateRequestIfNecessary >> informações adicionais do usuário não encontradas UserId[${user.id}]")
            return
        }

        if (userFacematchValidationManagerService.canUserUseFacematch(user.id)) return

        List<UserDocumentAdapter> userDocumentAdapterList = []
        UserDocumentFileAdapter selfieUserDocumentFile = new UserDocumentFileAdapter(authorizationDeviceUpdateRequest)
        UserDocumentAdapter selfieUserDocument = new UserDocumentAdapter(user, UserDocumentType.IDENTIFICATION_SELFIE, UserDocumentOrigin.AUTHORIZATION_DEVICE_UPDATE_REQUEST, [selfieUserDocumentFile])

        userDocumentAdapterList.add(selfieUserDocument)
        userDocumentAdapterList += buildIdentificationUserDocumentListFromCustomer(user, false)

        userAdditionalInfoManagerService.saveDocumentList(user, userDocumentAdapterList)
    }

    private List<CustomerDocumentAdapter> buildApprovedCustomerDocumentList(Customer customer, List<CustomerDocumentType> customerDocumentTypeList) {
        CustomerDocumentGroupType identificationGroupType = customerDocumentGroupProxyService.getIdentificationGroupType(customer)
        if (!identificationGroupType) return null

        return customerDocumentProxyService.list(customer.id, [
            "group.type": identificationGroupType,
            typeList: customerDocumentTypeList,
            status: CustomerDocumentStatus.APPROVED
        ])
    }

    private List<AsaasError> validateSendParams(Map params, UserDocumentType userDocumentType) {
        final Long maxFileSizeBytes = 26214400

        FileValidator fileValidator = new FileValidator()

        List<AsaasError> asaasErrorList = []

        if (!params.frontFile) {
            asaasErrorList.add(new AsaasError("UserDocument.send.validation.noFrontFile"))
            return asaasErrorList
        } else {
            Boolean isValid = fileValidator.validate(null, params.frontFile, maxFileSizeBytes)
            if (!isValid) return fileValidator.errors
        }

        if (userDocumentType.isIdentification()) {
            if (!params.backFile) {
                asaasErrorList.add(new AsaasError("UserDocument.send.validation.noBackFile"))
                return asaasErrorList
            } else {
                Boolean isValid = fileValidator.validate(null, params.backFile, maxFileSizeBytes)
                if (!isValid) return fileValidator.errors
            }
        }

        return asaasErrorList
    }

    private Map parseSendParams(Map params) {
        Map parsedParams = [:]
        if (params.temporaryFileFrontId) {
            parsedParams.frontFile = TemporaryFile.get(Long.valueOf(params.temporaryFileFrontId))
        }

        if (params.temporaryFileBackId) {
            parsedParams.backFile = TemporaryFile.get(Long.valueOf(params.temporaryFileBackId))
        }

        return parsedParams
    }

    private void deleteTemporaryFilesAfterSuccessfulSave(TemporaryFile frontTemporaryFile, TemporaryFile backTemporaryFile) {
        if (frontTemporaryFile) {
            fileService.removeTemporaryFile(frontTemporaryFile)
        }

        if (backTemporaryFile) {
            fileService.removeTemporaryFile(backTemporaryFile)
        }
    }

    private UserDocumentAdapter buildUserDocumentFromUserAdditionalInfo(User user, UserDocumentType userDocumentType, UserDocumentOrigin origin, TemporaryFile frontFile, TemporaryFile backFile) {
        List<UserDocumentFileAdapter> userDocumentFileAdapterList = []
        if (userDocumentType.isIdentificationSelfie()) {
            userDocumentFileAdapterList.add(new UserDocumentFileAdapter(frontFile, null))
        } else {
            userDocumentFileAdapterList.add(new UserDocumentFileAdapter(frontFile, UserDocumentFileType.FRONT))
            userDocumentFileAdapterList.add(new UserDocumentFileAdapter(backFile, UserDocumentFileType.BACK))
        }

        return new UserDocumentAdapter(user, userDocumentType, origin, userDocumentFileAdapterList)
    }

    private List<UserDocumentAdapter> buildIdentificationUserDocumentListFromCustomer(User user, Boolean shouldBuildSelfie) {
        Customer customer = user.customer
        List<CustomerDocumentAdapter> approvedIdentificationDocumentList = buildApprovedCustomerDocumentList(customer, CustomerDocumentType.identificationTypes())
        if (!approvedIdentificationDocumentList) return []

        List<UserDocumentAdapter> userDocumentAdapterList = []

        CustomerDocumentAdapter identificationDocumentAdapter = approvedIdentificationDocumentList.find { it.type.isIdentification() }
        UserDocumentAdapter identificationUserDocument = buildIdentificationUserDocumentIfPossible(user, identificationDocumentAdapter)
        if (identificationUserDocument) {
            userDocumentAdapterList.add(identificationUserDocument)
        }

        if (!shouldBuildSelfie) return userDocumentAdapterList

        CustomerDocumentAdapter identificationSelfieDocumentAdapter = approvedIdentificationDocumentList.find { it.type.isIdentificationSelfie() }
        UserDocumentAdapter identificationSelfieUserDocument = buildIdentificationSelfieUserDocumentIfPossible(user, identificationSelfieDocumentAdapter)
        if (identificationSelfieUserDocument) {
            userDocumentAdapterList.add(identificationSelfieUserDocument)
        }

        return userDocumentAdapterList
    }

    private UserDocumentAdapter buildIdentificationUserDocumentIfPossible(User user, CustomerDocumentAdapter identificationDocumentAdapter) {
        if (!identificationDocumentAdapter) return null

        List<UserDocumentFileAdapter> identificationUserDocumentFileList = []

        Map searchParams = [customerDocumentId: identificationDocumentAdapter.id, status: CustomerDocumentStatus.APPROVED]
        List<CustomerDocumentFileAdapter> customerDocumentFileAdapterList = customerDocumentFileProxyService.list(user.customer.id, searchParams)
        if (!customerDocumentFileAdapterList) return null

        identificationUserDocumentFileList.add(new UserDocumentFileAdapter(customerDocumentFileAdapterList.first().lastCustomerDocumentFileVersion, UserDocumentFileType.FRONT))
        if (customerDocumentFileAdapterList.size() > 1) {
            identificationUserDocumentFileList.add(new UserDocumentFileAdapter(customerDocumentFileAdapterList[1].lastCustomerDocumentFileVersion, UserDocumentFileType.BACK))
        }

        return new UserDocumentAdapter(user, UserDocumentType.IDENTIFICATION, UserDocumentOrigin.ONBOARDING, identificationUserDocumentFileList)
    }

    private UserDocumentAdapter buildIdentificationSelfieUserDocumentIfPossible(User user, CustomerDocumentAdapter identificationSelfieDocumentAdapter) {
        if (!identificationSelfieDocumentAdapter) return null

        Map searchParams = [customerDocumentId: identificationSelfieDocumentAdapter.id, status: CustomerDocumentStatus.APPROVED]
        List<CustomerDocumentFileAdapter> customerDocumentFileAdapterList = customerDocumentFileProxyService.list(user.customer.id, searchParams)
        if (!customerDocumentFileAdapterList) return null

        UserDocumentFileAdapter userDocumentFileAdapter = new UserDocumentFileAdapter(customerDocumentFileAdapterList.first().lastCustomerDocumentFileVersion, null)

        return new UserDocumentAdapter(user, UserDocumentType.IDENTIFICATION_SELFIE, UserDocumentOrigin.ONBOARDING, [userDocumentFileAdapter])
    }

    private User findUserToReceiveDocumentsFromAuthorizationDeviceUpdateRequest(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        User user = authorizationDeviceUpdateRequest.authorizationDevice.userKnownDeviceOrigin?.user
        if (user) return user

        List<User> adminUserList = User.admin(authorizationDeviceUpdateRequest.customer, [:]).list(max: 2)
        if (!adminUserList) return null

        final Integer maxAdminUserListSizeEnabledToReceiveDocuments = 1
        if (adminUserList.size() > maxAdminUserListSizeEnabledToReceiveDocuments) return null

        return adminUserList.first()
    }
}
