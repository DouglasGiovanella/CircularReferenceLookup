package com.asaas.service.loginunlockrequest

import com.asaas.domain.customer.Customer
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.facematchcriticalaction.enums.FacematchCriticalActionStatus
import com.asaas.facematchvalidation.vo.FacematchValidationVO
import com.asaas.loginunlockrequest.LoginUnlockRequestStatus
import com.asaas.utils.CryptographyUtils
import grails.transaction.Transactional

@Transactional
class LoginUnlockRequestService {

    def createFacematchCriticalActionService
    def facematchCriticalActionService
    def loginUnlockRequestResultProcessService
    def modulePermissionService
    def userFacematchCriticalActionService

    public LoginUnlockRequest create(Customer customer) {
        LoginUnlockRequest loginUnlockRequest = new LoginUnlockRequest()
        loginUnlockRequest.customer = customer
        loginUnlockRequest.status = LoginUnlockRequestStatus.PENDING

        final Integer hashByteSize = 128
        loginUnlockRequest.publicId = CryptographyUtils.generateSecureRandom(hashByteSize)
        loginUnlockRequest.save(failOnError: true)

        return loginUnlockRequest
    }

    public Boolean userNeedsUnlockLogin(String username) {
        User user = User.activeByUsername(username).get()
        if (!user) return false

        Boolean hasLoginUnlockRequestInProgress = LoginUnlockRequest.query([exists: true,
                                                                            customer: user.customer,
                                                                            "status[in]": LoginUnlockRequestStatus.listInProgress()]).get().asBoolean()

        return hasLoginUnlockRequestInProgress
    }

    public Map receiveUnlockLoginData(User user, Boolean isMobile) {
        LoginUnlockRequest loginUnlockRequest = LoginUnlockRequest.query([customer: user.customer, "status[in]": LoginUnlockRequestStatus.listInProgress()]).get()
        if (!loginUnlockRequest) {
            throw new BusinessException("Conta não está com login bloqueado")
        }

        if (checkIfExistsFacematchInProgressByOtherAdminUser(user, loginUnlockRequest)) {
            throw new BusinessException("Login bloqueado. Outro usuário administrador já iniciou a validação de identidade para desbloqueio.")
        }

        Boolean canUserUnlock = canUserUnlock(user)

        Map unlockData = [:]
        unlockData.unlockLoginRequestStatus = loginUnlockRequest.status
        unlockData.unlockLoginRequestPublicId = loginUnlockRequest.publicId
        unlockData.unlockLoginRequestLastUpdated = loginUnlockRequest.lastUpdated
        unlockData.canUserUnlock = canUserUnlock

        if (!canUserUnlock) {
            unlockData.enabledUsernameList = userFacematchCriticalActionService.buildEnabledUsernameAdminList(user.customer)
            return unlockData
        }

        FacematchCriticalAction facematchCriticalAction = createFacematchCriticalActionService.saveIfNecessary(loginUnlockRequest, user)
        unlockData.facematchCriticalActionPublicId = facematchCriticalAction.publicId
        if (loginUnlockRequest.status.isPending()) {
            loginUnlockRequestResultProcessService.onFacematchCriticalActionCreation(facematchCriticalAction)
            unlockData.unlockLoginRequestStatus = loginUnlockRequest.status
        }

        FacematchValidationVO facematchValidationVO = facematchCriticalActionService.buildFacematchValidation(facematchCriticalAction.id, user, isMobile)
        unlockData.url = facematchValidationVO.url

        return unlockData
    }

    private Boolean checkIfExistsFacematchInProgressByOtherAdminUser(User user, LoginUnlockRequest loginUnlockRequest) {
        Map searchParams = [:]
        searchParams.exists = true
        searchParams.ignoreRequester = true
        searchParams.customer = user.customer
        searchParams."requester[ne]" = user
        searchParams."status[in]" = FacematchCriticalActionStatus.listInProgress()
        searchParams.loginUnlockRequest = loginUnlockRequest
        Boolean existsFacematchInProgressByOtherAdminUser = FacematchCriticalAction.query(searchParams).get().asBoolean()

        return existsFacematchInProgressByOtherAdminUser
    }

    private Boolean canUserUnlock(User user) {
        Boolean isAdminUser = modulePermissionService.allowed(user, "admin")
        if (!isAdminUser) return false

        Boolean canUserUnlock = userFacematchCriticalActionService.canUserUseFacematch(user)
        return canUserUnlock
    }
}
