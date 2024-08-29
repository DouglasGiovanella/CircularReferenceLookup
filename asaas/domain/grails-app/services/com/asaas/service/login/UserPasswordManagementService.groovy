package com.asaas.service.login

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.login.RegistrationCode
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.User
import com.asaas.domain.user.UserPasswordExpirationSchedule
import com.asaas.domain.user.UserPasswordHistory
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserPasswordValidator
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserPasswordManagementService {

    def accountSecurityEventService
    def loginSessionService
    def securityEventNotificationService
    def userApiKeyService
    def userMobileDeviceService
    def userKnownDeviceService
    def userPasswordExpirationScheduleService
    def userWebAuthenticationService

    public void resetPassword(User user, String password, RegistrationCode registrationCode) {
        updatePasswordAndInvalidateUserLoginSessions(user, password)
        registrationCode.delete()
    }

    public void validateAndUpdatePassword(String username, String password, String confirmedPassword) {
        List<AsaasError> errorList = UserPasswordValidator.validatePassword(username, password, confirmedPassword, true)
        if (errorList) throw new BusinessException(errorList.first().getMessage())

        User user = User.query([username: username, ignoreCustomer: true]).get()
        executeUpdatePassword(user, password)
    }

    public void updatePasswordAndInvalidateUserLoginSessions(User user, String password) {
        executeUpdatePassword(user, password)
        loginSessionService.invalidateUserLoginSessions(user.id)
    }

    public UserPasswordHistory savePasswordHistory(User user) {
        UserPasswordHistory userPasswordHistory = new UserPasswordHistory()

        userPasswordHistory.user = user
        userPasswordHistory.password = user.password

        userPasswordHistory.save(failOnError: true)

        return userPasswordHistory
    }

    public void resetAllUsersPassword(Customer customer) {
        for (User user in customer.getUsers()) {
            if (!user.isAsaasErpUser()) loginSessionService.invalidateUserLoginSessions(user.id)

            user.password = user.password + "_"
            user.save(failOnError: true)
        }
    }

    public void expireAllUsersPassword(Customer customer) {
        BusinessValidation businessValidation = canExpire(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        for (User user in customer.getUsers()) {
            if (!user.isAsaasErpUser()) expirePassword(user)
        }
    }

    public void expirePassword(User user) {
        if (!user) return

        if (!Utils.emailIsValid(user.username)) {
            AsaasLogger.info("UserPasswordManagementService.expirePassword >> usuario com email invalido impossibilidado de ter senha expirada. User [${user.id}] email [${user.username}]")
            return
        }

        user.passwordExpired = true
        user.save(failOnError: true)

        userWebAuthenticationService.invalidateLoginSession(user)
        userMobileDeviceService.invalidateAll(user)
        userApiKeyService.invalidateAll(user, false)
        userKnownDeviceService.deactivateAll(user.id)
    }

    public BusinessValidation canExpire(Customer customer) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (CustomerPartnerApplication.hasBradesco(customer.id)) {
            validatedBusiness.addError("userPasswordManagement.validation.bradesco")
            return validatedBusiness
        }

        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) {
            validatedBusiness.addError("userPasswordManagement.validation.whiteLabel")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private void executeUpdatePassword(User user, String password) {
        user.password = password
        user.passwordExpired = false
        user.save(flush: true)

        UserPasswordHistory userPasswordHistory = savePasswordHistory(user)
        userPasswordExpirationScheduleService.saveScheduleAsyncAction(user, UserPasswordExpirationSchedule.DEFAULT_PASSWORD_EXPIRATION_DAYS)
        securityEventNotificationService.notifyAndSaveHistoryAboutUserPasswordUpdated(user)
        accountSecurityEventService.save(userPasswordHistory)
    }
}
