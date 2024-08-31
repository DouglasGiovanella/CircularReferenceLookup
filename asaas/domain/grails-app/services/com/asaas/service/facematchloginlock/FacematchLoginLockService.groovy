package com.asaas.service.facematchloginlock

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.loginunlockrequest.LoginUnlockRequest
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.loginunlockrequest.LoginUnlockRequestStatus
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class FacematchLoginLockService {

    def customerAlertNotificationService
    def customerInteractionService
    def loginSessionService
    def loginUnlockRequestService
    def userFacematchCriticalActionService

    public BusinessValidation canLoginBeLocked(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (CustomerPartnerApplication.hasBradesco(customer.id)) {
            businessValidation.addError("customer.canLoginBeLocked.validateCustomer.isBradescoPartner")
            return businessValidation
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) {
            businessValidation.addError("customer.canLoginBeLocked.validateCustomer.isWhiteLabel")
            return businessValidation
        }

        Boolean hasLoginUnlockRequestInProgress = LoginUnlockRequest.query([exists: true, customer: customer, "status[in]": LoginUnlockRequestStatus.listInProgress()]).get().asBoolean()
        if (hasLoginUnlockRequestInProgress) {
            businessValidation.addError("customer.canLoginBeLocked.validateCustomer.hasLoginUnlockRequestInProgress")
            return businessValidation
        }

        if (!isEnabledForFacematch(customer)) {
            businessValidation.addError("customer.canLoginBeLocked.validateCustomer.unabledForFacematch")
            return businessValidation
        }

        return businessValidation
    }

    public void lockAndRequestFacematch(Customer customer) {
        if (!canLoginBeLocked(customer)) {
            throw new BusinessException("Conta não pode ter login bloqueado")
        }

        loginSessionService.invalidateCustomerLoginSessions(customer)
        LoginUnlockRequest loginUnlockRequest = loginUnlockRequestService.create(customer)
        customerAlertNotificationService.notifyFacematchLoginUnlockRequested(customer, loginUnlockRequest)
        customerInteractionService.saveFacematchLoginLockRequest(customer)
    }

    private Boolean isEnabledForFacematch(Customer customer) {
        final Integer minNumberOfAdminUsersEnabledForFacematch = 1

        List<Long> adminUserIdList = User.admin(customer, [column: "id"]).list()
        List<Long> userIdListEnableForFacematch = userFacematchCriticalActionService.buildUserIdListEnabledForFacematch(adminUserIdList)

        return userIdListEnableForFacematch.size() >= minNumberOfAdminUsersEnabledForFacematch
    }
}
