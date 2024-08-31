package com.asaas.service.userupdaterequest

import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.user.User
import com.asaas.domain.user.UserUpdateRequest
import com.asaas.exception.BusinessException
import com.asaas.user.UserUpdateRequestStatus
import com.asaas.user.UserUtils
import com.asaas.user.adapter.UserAdapter
import com.asaas.utils.DomainUtils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserUpdateRequestService {

    def cancelFacematchCriticalActionService
    def createFacematchCriticalActionService
    def userService
    def userAdditionalInfoService
    def userFacematchElegibilityStatusService
    def userFacematchCriticalActionService

    public UserUpdateRequest save(UserAdapter userAdapter) {
        return save(userAdapter, false)
    }

    public UserUpdateRequest save(UserAdapter userAdapter, Boolean facematchCriticalActionByPass) {
        User user = User.get(userAdapter.id)

        UserUpdateRequest validatedUserUpdateRequest = validateSave(user, userAdapter)
        if (validatedUserUpdateRequest.hasErrors()) return validatedUserUpdateRequest

        Boolean isFacematchCriticalActionNecessary = false
        BusinessValidation criticalInfoChangedValidation
        if (!facematchCriticalActionByPass) {
            criticalInfoChangedValidation = checkIfCriticalInfoHasChanged(user, userAdapter)
            isFacematchCriticalActionNecessary = !criticalInfoChangedValidation.isValid()
        }
        if (!isFacematchCriticalActionNecessary) {
            return createAndAuthorize(user, userAdapter)
        }

        Boolean canUserUseFacematch = userFacematchCriticalActionService.canUserUseFacematch(user)
        if (!canUserUseFacematch) {
            userFacematchElegibilityStatusService.update(user, true)
            validatedUserUpdateRequest = new UserUpdateRequest()

            DomainUtils.addError(validatedUserUpdateRequest, criticalInfoChangedValidation.getFirstErrorMessage())
            return validatedUserUpdateRequest
        }

        return createWithFacematchCriticalAction(user, userAdapter)
    }

    public void onFacematchCriticalActionAuthorization(UserUpdateRequest userUpdateRequest) {
        userUpdateRequest.status = UserUpdateRequestStatus.APPROVED
        userUpdateRequest.save(failOnError: true)

        UserAdapter userAdapter = new UserAdapter(userUpdateRequest)
        userService.update(userAdapter)
    }

    public void onFacematchCriticalActionRejection(FacematchCriticalAction facematchCriticalAction) {
        UserUpdateRequest userUpdateRequest = facematchCriticalAction.userUpdateRequest

        cancel(userUpdateRequest)
    }

    public void cancel(UserUpdateRequest userUpdateRequest) {
        BusinessValidation businessValidation = userUpdateRequest.canBeCancelled()
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        userUpdateRequest.status = UserUpdateRequestStatus.CANCELLED
        userUpdateRequest.save(failOnError: true)

        cancelFacematchCriticalActionService.cancelUserUpdateRequestFacematchIfNecessary(userUpdateRequest)
    }

    private UserUpdateRequest createAndAuthorize(User user, UserAdapter userAdapter) {
        UserUpdateRequest userUpdateRequest = create(user, userAdapter)

        onFacematchCriticalActionAuthorization(userUpdateRequest)
        return userUpdateRequest
    }

    private UserUpdateRequest createWithFacematchCriticalAction(User user, UserAdapter userAdapter) {
        UserUpdateRequest userUpdateRequest = create(user, userAdapter)
        createFacematchCriticalActionService.saveUserUpdateRequestIfNecessary(userUpdateRequest, user)

        return userUpdateRequest
    }

    private UserUpdateRequest create(User user, UserAdapter userAdapter) {
        UserUpdateRequest userUpdateRequest = buildUserUpdateRequest(user, userAdapter)
        userUpdateRequest.save(failOnError: true)

        return userUpdateRequest
    }

    private UserUpdateRequest validateSave(User user, UserAdapter userAdapter) {
        UserUpdateRequest validatedUserUpdateRequest = new UserUpdateRequest()

        if (UserUtils.hasAsaasEmail(user.username)) {
            if (user.username != userAdapter.username) {
                DomainUtils.addError(validatedUserUpdateRequest, "Não é permitida alteração de e-mail para usuário Asaas.")
                return validatedUserUpdateRequest
            }
        }

        Boolean hasPendingUpdateRequest = UserUpdateRequest.awaitingFacematchAuthorization(userAdapter.id, [exists: true]).get().asBoolean()
        if (hasPendingUpdateRequest) {
            DomainUtils.addError(validatedUserUpdateRequest, "Já existe uma solicitação de alteração em andamento.")
            return validatedUserUpdateRequest
        }

        User validateUser = userService.validateSaveOrUpdateParams(userAdapter)
        if (validateUser.hasErrors()) {
            DomainUtils.copyAllErrorsFromObject(validateUser, validatedUserUpdateRequest)
            return validatedUserUpdateRequest
        }

        if (userAdapter.additionalInfoAdapter) {
            List<AsaasError> asaasErrorList = userAdditionalInfoService.validateSaveOrUpdateParams(userAdapter.additionalInfoAdapter)
            if (asaasErrorList) {
                DomainUtils.addError(validatedUserUpdateRequest, asaasErrorList.first().getMessage())
                return validatedUserUpdateRequest
            }
        }

        return validatedUserUpdateRequest
    }

    private BusinessValidation checkIfCriticalInfoHasChanged(User user, UserAdapter userAdapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (user.username && user.username != userAdapter.username) {
            businessValidation.addError("userUpdateRequest.validation.error.email.changed")
            return businessValidation
        }

        if (user.mobilePhone && userAdapter.mobilePhone && user.mobilePhone != userAdapter.mobilePhone) {
            businessValidation.addError("userUpdateRequest.validation.error.mobilePhone.changed")
            return businessValidation
        }

        return businessValidation
    }

    private UserUpdateRequest buildUserUpdateRequest(User user, UserAdapter userAdapter) {
        UserUpdateRequest userUpdateRequest = new UserUpdateRequest()

        userUpdateRequest.status = UserUpdateRequestStatus.AWAITING_FACEMATCH_CRITICAL_ACTION_AUTHORIZATION
        userUpdateRequest.user = user
        userUpdateRequest.username = userAdapter.username
        userUpdateRequest.name = userAdapter.additionalInfoAdapter?.name ?: user.name
        userUpdateRequest.workspace = userAdapter.workspace
        userUpdateRequest.mobilePhone = userAdapter.mobilePhone ?: user.mobilePhone
        userUpdateRequest.cpf = userAdapter.additionalInfoAdapter?.cpf
        userUpdateRequest.birthDate = userAdapter.additionalInfoAdapter?.birthDate
        userUpdateRequest.address = userAdapter.additionalInfoAdapter?.address
        userUpdateRequest.addressNumber = userAdapter.additionalInfoAdapter?.addressNumber
        userUpdateRequest.complement = userAdapter.additionalInfoAdapter?.complement
        userUpdateRequest.province = userAdapter.additionalInfoAdapter?.province
        userUpdateRequest.city = userAdapter.additionalInfoAdapter?.city
        userUpdateRequest.postalCode = userAdapter.additionalInfoAdapter?.postalCode
        userUpdateRequest.isPoliticallyExposedPerson = userAdapter.additionalInfoAdapter?.isPoliticallyExposedPerson
        userUpdateRequest.incomeRange = userAdapter.additionalInfoAdapter?.incomeRange
        userUpdateRequest.disabilityType = userAdapter.additionalInfoAdapter?.disabilityType
        userUpdateRequest.disabilityTypeDescription = userAdapter.additionalInfoAdapter?.disabilityTypeDescription

        return userUpdateRequest
    }
}
