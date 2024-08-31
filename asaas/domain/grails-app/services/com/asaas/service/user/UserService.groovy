package com.asaas.service.user

import com.asaas.customer.CustomerParameterName
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerRegisterStatus
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.Role
import com.asaas.domain.user.User
import com.asaas.domain.user.UserPasswordExpirationSchedule
import com.asaas.domain.user.UserRole
import com.asaas.domain.user.UserUpdateRequest
import com.asaas.exception.BusinessException
import com.asaas.exception.UserAdditionalInfoException
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.log.AsaasLogger
import com.asaas.user.UserPasswordValidator
import com.asaas.user.adapter.UserAdapter
import com.asaas.user.adapter.UserAdditionalInfoAdapter
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserService {

    def asaasErpUserAsyncActionService
    def createAsaasUserOnNexinvoiceAsyncActionService
    def asaasSecurityMailMessageService
    def connectedAccountInfoHandlerService
    def customerMessageService
	def messageService
    def mfaService
    def loginSessionService
    def registrationCodeService
    def securityEventNotificationService
    def userAdditionalInfoService
    def userPasswordManagementService
    def userPasswordExpirationScheduleService
    def userWebAuthenticationService
    def nexinvoiceUserDisableAsyncActionService

	public User findUser(Long id, Long providerId) {
		List<User> users = User.executeQuery('from User u where u.id = :id and u.customer.id = :providerId', [id: id, providerId: providerId])

		if (users == null || users.size() == 0) throw new RuntimeException("Usuário inexistente.")

		return users.get(0)
	}

    public void sendSetPasswordMailForNewInternalUser(User user) {
        String url = registrationCodeService.buildResetPasswordUrl(user)
        messageService.sendSetPasswordMailForNewInternalUser(url, user)
    }

	public User save(UserAdapter userAdapter) {
        User validatedUser = new User()

        BusinessValidation businessValidation = canAddUser(userAdapter.customer)
        if (!businessValidation.isValid()) {
            DomainUtils.addError(validatedUser, businessValidation.asaasErrors[0].getMessage())
            return validatedUser
        }

        validatedUser = validateSaveOrUpdateParams(userAdapter)
        if (validatedUser.hasErrors()) return validatedUser

        User user = new User()
        user.username = userAdapter.username
        user.createdBy = userAdapter.createdBy
        user.password = userAdapter.password
        user.customer = userAdapter.customer
        user.name = userAdapter.name
        user.mobilePhone = userAdapter.mobilePhone
        user.workspace = userAdapter.workspace

        user.save(flush: true)

		if (user.hasErrors()) return user

        if (userAdapter.additionalInfoAdapter) {
            userAdapter.additionalInfoAdapter.userId = user.id
        }

        UserAdditionalInfoAdapter userAdditionalInfo = saveUserAdditionalInfoIfNecessary(userAdapter)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(user, userAdditionalInfo))

        RoleAuthority authority = userAdapter.authority ?: RoleAuthority.ROLE_ADMIN
        createUserRole(user, authority)

		if (!userAdapter.disableNewUserNotification) sendAddUserMail(user)

        if (!userAdapter.disableAlertNotificationAboutUserCreation) {
            securityEventNotificationService.notifyAndSaveHistoryAboutUserCreation(user)
        }

        userPasswordManagementService.savePasswordHistory(user)
        userPasswordExpirationScheduleService.saveScheduleAsyncAction(user, UserPasswordExpirationSchedule.PASSWORD_EXPIRATION_DAYS_FOR_NEW_USERS)

        mfaService.registerAndEnableMfaIfNecessary(user)
        asaasErpUserAsyncActionService.saveIfNecessary(user, authority.toString())
        createAsaasUserOnNexinvoiceAsyncActionService.saveIfNecessary(user)

		return user
	}

    public void saveMobilePhoneAndEnableMfaIfPossible(Customer customer, String mobilePhone) {
        List<User> userList = User.query([customer: customer]).list(max: 2)
        if (userList.size() > 1) return

        User user = userList.first()
        if (user && !user.mobilePhone) {
            user.mobilePhone = mobilePhone
            user.save(failOnError: true)

            mfaService.registerAndEnableMfaIfNecessary(user)
        }
    }

	public User update(UserAdapter adapter) {
       return executeUpdate(User.find(adapter.id, adapter.customer), adapter)
	}

	public List<User> list(Long providerId, Integer max, Integer offset) {
        List<User> list = User.createCriteria().list(max: max, offset: offset) {
			eq("customer.id", providerId)
			eq("deleted", false)
		}
		return list
	}

    public List<User> listUserWithoutRoleAuthority(Customer customer, RoleAuthority roleAuthority, Integer max, Integer offset) {
        return User.query([customer: customer, "role[notExists]": roleAuthority.toString()]).list(readOnly: true, max: max, offset: offset)
    }

    public void forgotPassword(String username) {
        if (!username || !Utils.emailIsValid(username)) {
            throw new BusinessException(Utils.getMessageProperty("password.forgot.invalid.email"))
        }

        User user = User.notExpiredOrLocked(username).get()

        if (!user) {
            AsaasLogger.warn("UserService.forgotPassword -> Usuário não encontrado na tentativa de recuperação de senha: ${username}")
            return
        }

        if (CustomerPartnerApplication.hasBradesco(user.customerId)) {
            AsaasLogger.warn("UserService.forgotPassword >> Tentativa de redefinicao de senha por conta Bradesco. User [${user.id}].")
            return
        }

        if (shouldAlertWhiteLabelAccountOwner(user)) {
            customerMessageService.notifyAccountOwnerAboutPasswordRecoveryAttempt(user)
        } else {
            String url = registrationCodeService.buildResetPasswordUrl(user)
            asaasSecurityMailMessageService.sendForgotPasswordMail(user, url)
            securityEventNotificationService.notifyAndSaveHistoryAboutPasswordRecoveryAttempt(user)
        }
    }

    public void sendWelcomeToChildAccount(Customer customer) {
        User user = User.query([customer: customer]).get()
        String url = registrationCodeService.buildResetPasswordUrl(user)

        customerMessageService.sendWelcomeToChildAccount(customer, url)
    }

    public User deleteIfPossible(Long id, Long customerId) {
        BusinessValidation businessValidation = canDeleteUser(customerId)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        return delete(id, customerId, false)
    }

    public void deleteUsersOnAccountDisable(Long customerId) {
        for (User user : list(customerId, 100, 0)) {
            delete(user.id, customerId, true)
        }
    }

	public void lock(Map params) {
		User user = findBy(params)
		if (!user) throw new BusinessException("Usuário inexistente")

        AsaasLogger.info("Locking user >> ${user.username}")

		user.accountLocked = true
		user.save(flush: true, failOnError: true)
	}

	public void unlock(Map params) {
		User user = findBy(params)
		if (!user) throw new BusinessException("Usuário inexistente")

        AsaasLogger.info("Unlocking user >> ${user.username}")

		user.accountLocked = false
		user.save(flush: true, failOnError: true)
	}

	public Boolean isEmailInUse(String email) {
        return User.query([exists: true, username: email.trim(), ignoreCustomer: true]).get().asBoolean()
	}

	public Boolean validateEmailInUse(String email) {
        Boolean inUse = User.query([exists: true, username: email.trim(), ignoreCustomer: true]).get().asBoolean()
        if (inUse) return false

        return true
	}

	public Map changePassword(User user, Map params) {
        if (!UserPasswordValidator.isPasswordValid(user, params.currentPassword)) {
            return [messageCode: 'password.incorrect.current']
        }

        return saveNewPassword(user, params.newPassword, params.newPasswordConfirm)
	}

    public Map saveNewPassword(User user, String newPassword, String newPasswordConfirm) {
        Map responseMap = [:]

        List<AsaasError> errorList = UserPasswordValidator.validatePassword(user.email, newPassword, newPasswordConfirm, true)
        if (errorList) {
            responseMap.messageCode = errorList[0].code
            responseMap.args =  errorList[0].arguments
            return responseMap
        }

        userPasswordManagementService.updatePasswordAndInvalidateUserLoginSessions(user, newPassword)

        userWebAuthenticationService.authenticate(user.username, newPassword)

        responseMap.messageCode = "password.save.success"
        responseMap.success = true

        return responseMap
    }

    public List<String> getAdminUsernames(Customer customer) {
        List<String> adminUsersEmailList = []
        List<User> customerUsers = User.query([customer: customer]).list(readonly: true)

        for (User user : customerUsers) {
            Boolean hasUserAdminAuthority = user.getAuthorities().any { it.authority == RoleAuthority.ROLE_ADMIN.toString() }
            if (hasUserAdminAuthority) {
                adminUsersEmailList.add(user.username)
            }
        }

        return adminUsersEmailList.collect { it.toLowerCase() }
    }

    public BusinessValidation canAddUser(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.accountDisabled()) {
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        Boolean hasUsers = customer.getUsers().asBoolean()
        if (!hasUsers) {
            return businessValidation
        }

        if (CustomerPartnerApplication.hasBradesco(customer.id)) {
            businessValidation.addError("default.notAllowed.message")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation canUpdateUser(User user) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean hasPendingUpdateRequest = UserUpdateRequest.awaitingFacematchAuthorization(user.id, [exists: true]).get().asBoolean()
        if (hasPendingUpdateRequest) {
            businessValidation.addError("user.update.error.UserUpdateRequest.pending", [user.username])
        }

        return businessValidation
    }

    public BusinessValidation canDeleteUser(Long customerId) {
        BusinessValidation businessValidation = new BusinessValidation()

        GeneralApprovalStatus generalApprovalStatus = CustomerRegisterStatus.query([column: "generalApproval", customerId: customerId]).get()
        if (!generalApprovalStatus.isApproved()) {
            businessValidation.addError("user.delete.error.customerHasNotGeneralApproval")
            return businessValidation
        }

        return businessValidation
    }

    public User createFirstUser(UserAdapter userAdapter) {
        if (!validateEmailInUse(userAdapter.customer.email)) throw new RuntimeException("Usuário com email [${userAdapter.customer.email}] já existente.")

        User user = save(userAdapter)
        if (user.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(user))

        if (userAdapter.registerIp) {
            user.lastLogin = new Date()
            user.lastLoginIp = userAdapter.registerIp
            user.save(failOnError: true)
        }

        return user
    }

    public User validateSaveOrUpdateParams(UserAdapter userAdapter) {
        User validatedUser = new User()

        if (Utils.isEmptyOrNull(userAdapter.username)) {
            DomainUtils.addFieldError(validatedUser, "username", "required")
            return validatedUser
        } else if (!Utils.emailIsValid(userAdapter.username)) {
            DomainUtils.addFieldError(validatedUser, "username", "invalid")
            return validatedUser
        } else if (Utils.isEmptyOrNull(userAdapter.id)) {
            if (User.usernameIsAlreadyInUse(userAdapter.username)) {
                DomainUtils.addFieldError(validatedUser, "username", "alreadyInUse")
                return validatedUser
            }
        }

        if (!Utils.isEmptyOrNull(userAdapter.mobilePhone)) {
            if (!PhoneNumberUtils.validateMobilePhone(userAdapter.mobilePhone)) {
                DomainUtils.addFieldError(validatedUser, "mobilePhone", "invalid")
                return validatedUser
            }
        }

        if (!User.validateIfCanEnableWorkspace(userAdapter.workspace, userAdapter.authority?.toString())) {
            DomainUtils.addFieldError(validatedUser, "workspace", "cannotEnableWorkspace")
            return validatedUser
        }

        return validatedUser
    }

    private User executeUpdate(User user, UserAdapter userAdapter) {
        BusinessValidation businessValidation = canUpdateUser(user)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.asaasErrors[0].getMessage())

        User validateUser = validateSaveOrUpdateParams(userAdapter)
        if (validateUser.hasErrors()) return validateUser

        ConnectedAccountInfoAdapter infoToBeCompared = new ConnectedAccountInfoAdapter(user)

        if (userAdapter.username != null) {
            user.username = userAdapter.username
        }

        if (userAdapter.name != null) {
            user.name = userAdapter.name
        }

        if (userAdapter.workspace != null) {
            user.workspace = userAdapter.workspace
        }

        if (userAdapter.mobilePhone != null) {
            user.mobilePhone = userAdapter.mobilePhone
        }

        Map updatedProperties = buildUserUpdatedPropertiesMap(user)

        user.save(flush: true, failOnError: false)
        if (user.hasErrors()) return user

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(user), infoToBeCompared)
        saveUserAdditionalInfoIfNecessary(userAdapter)

        if (userAdapter.authority) {
            Role currentAuthority = user.getCurrentAuthority()

            if (currentAuthority.authority != userAdapter.authority.toString()) {
                loginSessionService.invalidateUserLoginSessions(user.id)
                updatedProperties.put("authority", userAdapter.authority.toString())
            }

            updateUserAuthority(user, userAdapter.authority)
        }

        mfaService.registerAndEnableMfaIfNecessary(user)
        asaasErpUserAsyncActionService.updateIfNecessary(user, updatedProperties)
        securityEventNotificationService.notifyAndSaveHistoryAboutUserUpdated(user, userAdapter.updatedBy)

        return user
    }

    private User delete(Long id, Long customerId, Boolean isAccountDisable) {
        User user = findUser(id, customerId)

        registrationCodeService.deleteAllNotExpired(user.username)

        List<UserRole> userRoleList = UserRole.query([user: user]).list()
        for (UserRole userRole in userRoleList) {
            userRole.delete(flush: true)
        }

        user.deleted = true
        user.username = new Date().getTime() + "_" + user.username
        user.save(flush: true, failOnError: true)

        userAdditionalInfoService.deleteIfNecessary(user)

        loginSessionService.invalidateUserLoginSessions(user.id)
        if (!isAccountDisable) asaasErpUserAsyncActionService.deleteIfNecessary(user)
        nexinvoiceUserDisableAsyncActionService.deleteIfNecessary(user)

        return user
    }

    private User findBy(Map params) {
        if (params.id) {
            return User.findWhere(id: params.id instanceof Long ? params.id : params.long("id"))
        } else if (params.email) {
            return User.findWhere(username: params.email)
        }
    }

    private void sendAddUserMail(User user) {
        String url = registrationCodeService.buildResetPasswordUrl(user)
        asaasSecurityMailMessageService.sendAddUserMail(url, user)
    }

    private Boolean shouldAlertWhiteLabelAccountOwner(User user) {
        Boolean hasWhiteLabel = CustomerParameter.getValue(user.customer, CustomerParameterName.WHITE_LABEL)
        return hasWhiteLabel && user.customer.accountOwner
    }

    private UserAdditionalInfoAdapter saveUserAdditionalInfoIfNecessary(UserAdapter userAdapter) {
        UserAdditionalInfoAdapter userAdditionalInfoAdapter = userAdapter.additionalInfoAdapter

        if (!userAdditionalInfoAdapter) return null

        try {
            return userAdditionalInfoService.save(userAdditionalInfoAdapter)
        } catch (UserAdditionalInfoException userAdditionalInfoException) {
            AsaasLogger.error("UserService.saveUserAdditionalInfoIfNecessary > UserAdditionalInfoException UserID[${userAdditionalInfoAdapter.userId}]", userAdditionalInfoException)
        }
    }

    private Map buildUserUpdatedPropertiesMap(User user) {
        List<Map> userUpdatedFieldList = DomainUtils.getUpdatedFields(user)
        Map updatedProperties = [:]

        for (Map updatedProperty : userUpdatedFieldList) {
            updatedProperties.put(updatedProperty.fieldName, updatedProperty.newValue)
        }

        return updatedProperties
    }

    private void createUserRole(User user, RoleAuthority authority) {
        if (!Role.getValidAuthoritiesForNotSysAdminUsers().contains(authority.toString())) throw new BusinessException("Papel de usuário inválido.")

        UserRole defaultUserRole = new UserRole(user: user, role: Role.findByAuthority(RoleAuthority.ROLE_USER.toString()))
        defaultUserRole.save(flush: true, failOnError: true)

        UserRole customUserRole = new UserRole(user: user, role: Role.findByAuthority(authority.toString()))
        customUserRole.save(flush: true, failOnError: true)
    }

    private void updateUserAuthority(User user, RoleAuthority authority) {
        UserRole userRole = UserRole.findWhere(user: user, role: user.getCurrentAuthority())

        if (userRole) {
            userRole.delete(flush: true)
        }

        Role newRole = Role.findWhere(authority: authority.toString())

        UserRole newUserRole = new UserRole(user: user, role: newRole)
        newUserRole.save(failOnError: true)
    }
}
