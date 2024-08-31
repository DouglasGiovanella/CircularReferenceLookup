package com.asaas.service.userImpersonationRequest

import com.asaas.log.AsaasLogger
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.userImpersonationRequest.UserImpersonationRequest
import com.asaas.domain.user.User
import com.asaas.domain.customer.Customer
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils

import grails.plugin.springsecurity.SpringSecurityUtils

import grails.transaction.Transactional

@Transactional
class UserImpersonationRequestService {

    def adminAccessTrackingService
    def customerAlertNotificationService
    def mobilePushNotificationService
    def userWebAuthenticationService

    public UserImpersonationRequest save(User asaasUser, Customer customer) {
        final Integer expirationTimeInMinutes = 30

        if (!canLoginAsCustomer(customer)) {
            throw new RuntimeException("Você não tem permissão para logar como cliente: [${customer.id}], currentUserId: [${asaasUser.id}].")
        }

        User impersonatedUser = User.admin(customer, [:]).get()
        AccountManager accountManager = AccountManager.query([user: UserUtils.getCurrentUser()]).get()
        UserImpersonationRequest validateUserImpersonationRequest = validateSave(accountManager)

        if (validateUserImpersonationRequest.hasErrors()) {
            return validateUserImpersonationRequest
        }

        cancelPreviousRequestIfNecessary(impersonatedUser)

        UserImpersonationRequest userImpersonationRequest = new UserImpersonationRequest()

        userImpersonationRequest.user = asaasUser
        userImpersonationRequest.impersonatedUser = impersonatedUser
        userImpersonationRequest.expirationDate = CustomDateUtils.sumMinutes(new Date(), expirationTimeInMinutes)

        userImpersonationRequest.save(failOnError: true)

        String accountManagerName = accountManager.name
        notifyCustomer(userImpersonationRequest, accountManagerName)

        return userImpersonationRequest
    }

    public UserImpersonationRequest authorize(Long id, Customer customer) {
        UserImpersonationRequest userImpersonationRequest = UserImpersonationRequest.query([id: id, customer: customer]).get()

        Date dateTimeNow = new Date()

        if (!userImpersonationRequest) {
            userImpersonationRequest = new UserImpersonationRequest()
            DomainUtils.addError(userImpersonationRequest, "Esta solicitação é inválida.")
        } else if (userImpersonationRequest.expirationDate < dateTimeNow) {
            DomainUtils.addError(userImpersonationRequest, "Esta solicitação está expirada.")
        } else {
            userImpersonationRequest.authorized = true
            userImpersonationRequest.save(failOnError: true)
        }

        return userImpersonationRequest
    }

    public UserImpersonationRequest loginAsCustomer(User asaasUser, Customer customer, Map params) {
        User impersonatedUser = User.admin(customer, [:]).get()
        UserImpersonationRequest userImpersonationRequest = UserImpersonationRequest.notExpired([impersonatedUser: impersonatedUser, user: asaasUser, authorized: true]).get()

        if (!userImpersonationRequest) {
            UserImpersonationRequest validatedUserImpersonationRequest = new UserImpersonationRequest()
            DomainUtils.addError(validatedUserImpersonationRequest, "O cliente ainda não autorizou sua solicitação.")

            return validatedUserImpersonationRequest
        }

        reauthenticate(asaasUser, impersonatedUser, params)

        userImpersonationRequest.deleted = true
        userImpersonationRequest.save(failOnError: true)

        return userImpersonationRequest
    }

    public void bypassLoginAsCustomer(User asaasUser, Customer customer, Map params) {
        if (!canLoginAsCustomer(customer)) {
            throw new RuntimeException("Você não tem permissão para logar como cliente: [${customer.id}], currentUserId: [${asaasUser.id}].")
        }

        if (!AdminUserPermissionUtils.canBypassLoginAsCustomer(asaasUser)) {
            throw new RuntimeException("Usuário não tem permissão bypass para logar como cliente: [${customer.id}], currentUserId: [${asaasUser.id}].")
        }

        User impersonatedUser = User.admin(customer, [:]).get()
        reauthenticate(asaasUser, impersonatedUser, params)
    }

    public Boolean canLoginAsCustomer(Customer customer) {
        Boolean isSysAdmin = SpringSecurityUtils.ifAllGranted("ROLE_SYSADMIN")
        if (!isSysAdmin) return false

        Boolean isAsaasCustomer = customer.hasUserWithSysAdminRole()
        if (isAsaasCustomer) return false

        User adminUser = User.admin(customer, [:]).get()
        return adminUser.asBoolean()
    }

    private UserImpersonationRequest validateSave(AccountManager accountManager) {
        UserImpersonationRequest userImpersonationRequest = new UserImpersonationRequest()

        if (!accountManager) {
            DomainUtils.addError(userImpersonationRequest, "Você precisa ser um gerente de contas para efetuar essa operação.")
        }

        return userImpersonationRequest
    }

    private void notifyCustomer(UserImpersonationRequest userImpersonationRequest, String accountManagerName) {
        Customer customer = userImpersonationRequest.impersonatedUser.customer

        customerAlertNotificationService.notifyCustomerAboutUserImpersonationRequest(customer, userImpersonationRequest.id, accountManagerName)
        mobilePushNotificationService.notifyCustomerAboutUserImpersonationRequest(customer, userImpersonationRequest.id, accountManagerName)
    }

    private void cancelPreviousRequestIfNecessary(User impersonatedUser) {
        UserImpersonationRequest previousUserImpersonationRequest = UserImpersonationRequest.query([impersonatedUser: impersonatedUser]).get()
        if (previousUserImpersonationRequest) {
            previousUserImpersonationRequest.deleted = true
            previousUserImpersonationRequest.save(failOnError: true)
        }
    }

    private void reauthenticate(User asaasUser, User impersonatedUser, Map params) {
        try {
            adminAccessTrackingService.save(params, asaasUser.id, impersonatedUser.id.toString(), impersonatedUser.customer.id)
            userWebAuthenticationService.reauthenticate(impersonatedUser.id)
        } catch (Exception e) {
            AsaasLogger.error("Problema ao tentar logar como cliente: [${impersonatedUser.customer.id}], currentUserId: [${asaasUser.id}]", e)
        }
    }
}
