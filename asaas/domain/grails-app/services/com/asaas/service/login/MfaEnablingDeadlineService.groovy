package com.asaas.service.login

import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorDevice
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class MfaEnablingDeadlineService {

    private final Integer NUMBER_OF_DAYS_FOR_MFA_DEAD_LINE = 7

    public void save(Long mfaUserId) {
        User user = User.get(mfaUserId)

        User validatedUser = validate(user)
        if (validatedUser.hasErrors()) throw new ValidationException("Erro ao adiar temporariamente a obrigatoriedade do MFA", validatedUser.errors)

        user.mfaEnablingDeadline = CustomDateUtils.sumDays(new Date(), NUMBER_OF_DAYS_FOR_MFA_DEAD_LINE)
        user.mfaEnabled = false
        user.save(failOnError: true)

        invalidateCurrentUserMultiFactorDevice(user)
    }

    public Boolean hasMfaEnablingDeadlineActive(User mfaUser) {
        if (!mfaUser.sysAdmin()) return false

        if (!mfaUser.mfaEnablingDeadline) return false

        return mfaUser.mfaEnablingDeadline >= new Date().clearTime()
    }

    private User validate(User mfaUser) {
        User user = new User()

        if (!mfaUser) {
            DomainUtils.addError(user, "O usuário deve ser informado.")
            return user
        }

        if (!mfaUser.sysAdmin()) {
            DomainUtils.addError(user, "Somente usuários administrativos podem ter o adiamento temporário do MFA ativado.")
        }

        if (hasMfaEnablingDeadlineActive(mfaUser)) {
            DomainUtils.addError(user, "Esse usuário já possui adiamento temporário do MFA ativo.")
        }

        return user
    }

    private void invalidateCurrentUserMultiFactorDevice(User user) {
        UserMultiFactorDevice userMultiFactorDevice = UserMultiFactorDevice.query([user: user]).get()
        if (userMultiFactorDevice) {
            userMultiFactorDevice.deleted = true
            userMultiFactorDevice.save(failOnError: true)
        }
    }
}
