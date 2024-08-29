package com.asaas.service.user

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.user.adapter.UserAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class UserCriticalActionService {

    def criticalActionNotificationService
    def criticalActionService
    def userService

    public User validateTokenAndSaveOrUpdate(Long userId, User currentUser, Map params, String criticalActionToken) {
        Customer customer = currentUser.customer

        UserAdapter userAdapter

        if (userId) {
            params.id = userId
            userAdapter = new UserAdapter(params)
            userAdapter.customer = customer
            userAdapter.updatedBy = currentUser
            return validateTokenAndUpdate(userAdapter, criticalActionToken)
        }

        userAdapter = new UserAdapter(params)
        userAdapter.createdBy = currentUser
        userAdapter.disableAlertNotificationAboutUserCreation = false
        userAdapter.customer = customer
        userAdapter.password = UserUtils.generateRandomPassword()

        return validateTokenAndSave(customer, userAdapter, criticalActionToken)
    }

    public CriticalActionGroup saveUserInsertCriticalActionGroup(User user, Map params) {
        String hash = buildCriticalActionHash(user)
        String authorizationMessage = criticalActionNotificationService.buildSynchronousCriticalActionAuthorizationMessage(CriticalActionType.USER_INSERT, params)

        return criticalActionService.saveAndSendSynchronous(user.customer, CriticalActionType.USER_INSERT, hash, authorizationMessage)
    }

    public CriticalActionGroup saveUserUpdateCriticalActionGroup(User user, Map params) {
        String hash = buildCriticalActionHash(user)
        String authorizationMessage = criticalActionNotificationService.buildSynchronousCriticalActionAuthorizationMessage(CriticalActionType.USER_UPDATE, params)

        return criticalActionService.saveAndSendSynchronous(user.customer, CriticalActionType.USER_UPDATE, hash, authorizationMessage)
    }

    public void validateCriticalActionToken(User user, CriticalActionType criticalActionType, String criticalActionToken, Long criticalActionGroupId) {
        String hash = buildCriticalActionHash(user)

        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(user.customer.id, criticalActionGroupId, criticalActionToken, criticalActionType, hash)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }
    }

    private User validateTokenAndSave(Customer customer, UserAdapter userAdapter, String criticalActionToken) {
        if (Utils.isEmptyOrNull(userAdapter.criticalActionGroupId)) {
            throw new BusinessException("Para criar o usuário será necessário fazer a autorização via Token App ou Token SMS.")
        }

        BusinessValidation canAddUserBusinessValidation = userService.canAddUser(customer)
        if (!canAddUserBusinessValidation.isValid()) throw new BusinessException(canAddUserBusinessValidation.getFirstErrorMessage())

        validateCriticalActionToken(UserUtils.getCurrentUser(), CriticalActionType.USER_INSERT, criticalActionToken, userAdapter.criticalActionGroupId)

        return userService.save(userAdapter)
    }

    private User validateTokenAndUpdate(UserAdapter userAdapter, String criticalActionToken) {
        if (Utils.isEmptyOrNull(userAdapter.criticalActionGroupId)) {
            throw new BusinessException("Para atualizar o usuário será necessário fazer a autorização via Token App ou Token SMS.")
        }

        validateCriticalActionToken(UserUtils.getCurrentUser(), CriticalActionType.USER_UPDATE, criticalActionToken, userAdapter.criticalActionGroupId)

        return userService.update(userAdapter)
    }

    private String buildCriticalActionHash(User user) {
        String operation = new StringBuilder(user.id.toString())
            .append(user.customer.id.toString())
            .toString()

        return operation.encodeAsMD5()
    }
}
