package com.asaas.service.internaluser

import com.asaas.accountmanager.AccountManagerDepartment
import com.asaas.customer.CustomerValidator
import com.asaas.customer.adapter.InternalCustomerAdapter
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.Role
import com.asaas.domain.user.User
import com.asaas.domain.user.UserRole
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.user.adapter.InternalUserAdapter
import com.asaas.user.adapter.UserAdapter
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class InternalUserService {

    def accountManagerService
    def createCustomerService
    def customerAdminService
    def customerConfigService
    def customerParameterService
    def customerRegisterStatusService
    def feeAdminService
    def internalUserPermissionsService
    def internalUserPermissionBinderService
    def receivableAnticipationAnalystService
    def userService

    public void save(InternalUserAdapter internalUserAdapter, User currentUser) {
        User validatedUser = validateSave(internalUserAdapter)
        if (validatedUser.hasErrors()) throw new ValidationException("Não foi possível salvar os dados do usuário", validatedUser.errors)

        InternalCustomerAdapter customerAdapter = InternalCustomerAdapter.build(internalUserAdapter.properties)
        Customer customer = createCustomerService.save(customerAdapter)
        if (customer.hasErrors()) throw new ValidationException("Não foi possível salvar os dados do cliente", customer.errors)

        User user = User.query([customer: customer]).get()
        user.name = internalUserAdapter.internalName
        user.save(failOnError: true)

        internalUserPermissionsService.setPermissions(user, internalUserAdapter.roleAuthorityList, internalUserAdapter.adminUserPermissionNameList, internalUserAdapter.developerActionPermissionNameList, currentUser)

        customerRegisterStatusService.updateNewInternalUserStatus(user, internalUserAdapter.useFakeInfo)

        customerParameterService.saveInternalUserParameters(customer)

        if (internalUserAdapter.useFakeInfo) {
            customerAdminService.disableCheckout(customer.id, "Desabilitado saques de usuários fakes")
            customerConfigService.blockBoleto(customer.id)
        }

        if (internalUserAdapter.roleAuthorityList.contains(RoleAuthority.ROLE_CREDIT_ANALYST)) {
            receivableAnticipationAnalystService.saveIfNotExists(user)
        }

        feeAdminService.updateNewInternalUserFees(customer.id)

        userService.sendSetPasswordMailForNewInternalUser(user)
    }

    public void update(Long id, InternalUserAdapter internalUserAdapter, Long currentUserId) {
        User user = User.get(id)
        Customer customer = user.customer

        Boolean hasAccountManager = hasAccountManagerWithFakeInfo(id, internalUserAdapter.useFakeInfo)

        User validatedUser = validateUpdate(internalUserAdapter, customer, hasAccountManager)
        if (validatedUser.hasErrors()) throw new ValidationException("Não foi possível salvar os dados do usuário", validatedUser.errors)

        user.name = internalUserAdapter.internalName
        user.username = internalUserAdapter.email
        user.save(failOnError: true)

        customer.name = internalUserAdapter.useFakeInfo ? internalUserAdapter.fakeName : internalUserAdapter.internalName
        customer.email = internalUserAdapter.email
        customer.save(failOnError: true)

        internalUserPermissionsService.updatePermissions(user, internalUserAdapter.roleAuthorityList, internalUserAdapter.adminUserPermissionNameList, internalUserAdapter.developerActionPermissionNameList, currentUserId)

        if (internalUserAdapter.roleAuthorityList.contains(RoleAuthority.ROLE_CREDIT_ANALYST)) {
            receivableAnticipationAnalystService.saveIfNotExists(user)
        }
    }

    public List<User> list(Map search, String authority, Integer adminLimitPerPage, Integer currentPage) {
        Role role = Role.query([authority: authority ?: RoleAuthority.ROLE_SYSADMIN.toString()]).get()
        search."id[in]" = UserRole.query([column: "user.id", ignoreUser: true, role: role]).list()

        if (!search."id[in]") return null

        return User.query(search).list(max: adminLimitPerPage, offset: currentPage)
    }

    public List<User> listActiveUsers() {
        Role role = Role.query([authority: "ROLE_SYSADMIN"]).get()
        List<User> userList = UserRole.query([column: "user", ignoreUser: true, role: role]).list()

        List<User> activeUserList = userList.findAll { !it.isDisabled() }.sort { it.name }
        return activeUserList
    }

    public void disableUserAndAccountManager(User user, User currentUser) {
        lockAccountUserList(user)

        internalUserPermissionsService.deleteAdminPermissionsIfNecessary(user, currentUser)
        AccountManager accountManager = AccountManager.query([user: user]).get()
        if (accountManager) accountManagerService.disable(accountManager)
    }

    public void convertToPersonalAccount(Long customerId, String personalEmail, AccountManagerDepartment department, User currentUser) {
        Customer customer = Customer.query([id: customerId]).get()

        if (!customer) throw new BusinessException("Cliente não encontrado.")
        if (department?.shouldUseFakeInfo()) throw new BusinessException("Não é possível converter uma conta fake em uma conta pessoal.")

        User personalUser
        for (User user : User.active(customer).list()) {
            internalUserPermissionsService.deleteAdminPermissionsIfNecessary(user, currentUser)
            if (!user.username.contains("@asaas.com")) personalUser = user
        }

        if (!personalUser) {
            personalUser = createPersonalUser(customer, personalEmail)
            if (personalUser.hasErrors()) throw new ValidationException("Erro ao criar uma conta para conversão de conta Asaas em conta pessoal", personalUser.errors)
        }

        customer.email = personalUser.username
        customer.save(failOnError: true)

        feeAdminService.updateAllFeesToDefaultValues(customer, "conversão de conta Asaas em conta pessoal")
    }

    public void lockAccountUserList(User internalUser) {
        List<User> userList = User.query([customer: internalUser.customer]).list()

        for (User user : userList) {
            user.enabled = false
            user.accountLocked = true
            user.loginSessionVersion += 1

            user.save(failOnError: true)
        }
    }

    public void unlockAccountUserList(Long userId) {
        User internalUser = User.get(userId)
        List<User> userList = User.query([customer: internalUser.customer]).list()

        for (User user : userList) {
            user.enabled = true
            user.accountLocked = false

            user.save(failOnError: true)
        }
    }

    public BusinessValidation canTemporarilyLockUserAccess(Long userId) {
        User user = User.get(userId)
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (user.isDisabled()) validatedBusiness.addError("user.disabled")

        return validatedBusiness
    }

    public void expireUserPassword(Long userId) {
        User user = User.get(userId)

        user.passwordExpired = true
        user.loginSessionVersion += 1
        user.save(failOnError: true)
    }

    public BusinessValidation canExpireUserPassword(Long userId) {
        User user = User.get(userId)
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (user.isPasswordExpired()) validatedBusiness.addError("user.password.expired")

        return validatedBusiness
    }

    public List<Map> listWithoutAccountManager(String name, Integer limit) {
        Map search = [:]
        search.sort = "name"
        search.ignoreCustomer = true
        search.columnList = ["id", "name"]
        search."name[like]" = name
        search.enabled = true

        Role role = Role.query([authority: RoleAuthority.ROLE_SYSADMIN.toString()]).get()

        search."id[in]" = UserRole.query([column: "user.id", ignoreUser: true, "role": role]).list()
        search."id[notIn]" = AccountManager.query([column: "user.id"]).list()

        List<Map> userInfoList = User.query(search).list(max: limit)

        return userInfoList
    }

    public Boolean hasAccountManagerWithFakeInfo(Long userId, Boolean useFakeInfo) {
        if (!useFakeInfo) return false

        return AccountManager.query([column: "id", userId: userId]).get().asBoolean()
    }

    private User createPersonalUser(Customer customer, String email) {
        User validatedUser = validateUserEmail(email)
        if (validatedUser.hasErrors()) throw new ValidationException("Erro ao validar email de conta pessoal", validatedUser.errors)

        UserAdapter userAdapter = new UserAdapter()
        userAdapter.username = email
        userAdapter.password = UserUtils.generateRandomPassword()
        userAdapter.customer = customer
        userAdapter.disableAlertNotificationAboutUserCreation = true

        return userService.save(userAdapter)
    }

    private User validateUserEmail(String email) {
        User validatedUser = new User()
        CustomerValidator customerValidator = new CustomerValidator()
        BusinessValidation validatedEmail = customerValidator.validateEmailCanBeUsed(email, true)

        if (!validatedEmail.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedEmail, validatedUser)
		}

        if (UserUtils.isAsaasTeam(email)) {
            return DomainUtils.addError(validatedUser, "Não é possível converter para conta pessoal com email do domínio Asaas.")
        }

        return validatedUser
    }

    private validateSave(InternalUserAdapter internalUserAdapter) {
        User validatedUser = new User()

        if (!internalUserAdapter.internalName) {
            DomainUtils.addError(validatedUser, "O nome deve ser informado.")
        }

        if (!internalUserAdapter.email) {
            DomainUtils.addError(validatedUser, "O email deve ser informado.")
        } else if (!Utils.emailIsValid(internalUserAdapter.email)) {
            DomainUtils.addError(validatedUser, "O email informado é inválido.")
        } else if (!UserUtils.hasAsaasEmail(internalUserAdapter.email)) {
            DomainUtils.addError(validatedUser, "Não é possível cadastrar usuários administrativos com email interno fora do domínio Asaas.")
        }

        if (internalUserAdapter.roleAuthorityList && !RoleAuthority.isAdminRole(internalUserAdapter.roleAuthorityList)) {
            DomainUtils.addError(validatedUser, "A permissão selecionada não é uma permissão de usuário administrativo.")
        }

        if (internalUserAdapter.useFakeInfo && !internalUserAdapter.cpf) {
            DomainUtils.addError(validatedUser, "O CPF do colaborador deve ser informado ao cadastrar um usuário fake.")
        }

        return validatedUser
    }

    private User validateUpdate(InternalUserAdapter internalUserAdapter, Customer customer, Boolean hasAccountManager) {
        User validatedUser = validateSave(internalUserAdapter)

        if (!hasAccountManager) return validatedUser

        if (customer.email != internalUserAdapter.email) DomainUtils.addError(validatedUser, "Não é possível alterar o email de um usuário interno vinculado a um gerente de contas")

        if (customer.name != internalUserAdapter.fakeName) DomainUtils.addError(validatedUser, "Não é possível alterar o nome fake de um usuário interno vinculado a um gerente de contas")

        return validatedUser
    }
}
