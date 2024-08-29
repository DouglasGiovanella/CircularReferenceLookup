package com.asaas.service.user

import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.user.User
import com.asaas.domain.user.UserWorkingHours
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class UserWorkingHoursService {

    def accountManagerService

    public void update(Map params, User user) {
        Map parsedParams = parseDefaultParams(params)
        UserWorkingHours validatedDomain = validateSave(parsedParams)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        UserWorkingHours userWorkingHours = UserWorkingHours.query([user: user]).get()
        if (!userWorkingHours) {
            userWorkingHours = new UserWorkingHours()
            userWorkingHours.user = user
        }

        userWorkingHours.weekdaysStartHour = parsedParams.weekdaysStartHour
        userWorkingHours.weekdaysEndHour = parsedParams.weekdaysEndHour

        userWorkingHours.saturdayStartHour = parsedParams.saturdayStartHour
        userWorkingHours.saturdayEndHour = parsedParams.saturdayEndHour

        userWorkingHours.sundayStartHour = parsedParams.sundayStartHour
        userWorkingHours.sundayEndHour = parsedParams.sundayEndHour

        userWorkingHours.save(failOnError: true)

        AccountManager accountManager = AccountManager.query([user: user]).get()

        if (!accountManager) return

        accountManagerService.disableCustomerPortfolioIfNecessary(accountManager, true)
    }

    private UserWorkingHours validateSave(Map params) {
        UserWorkingHours userWorkingHours = new UserWorkingHours()

        if (params.weekdaysStartHour == null || params.weekdaysEndHour == null || !isValidHours(params.weekdaysStartHour, params.weekdaysEndHour)) {
            DomainUtils.addError(userWorkingHours, "O horário de segunda a sexta inválido.")
        }

        if (!isValidWeekendHours(params.saturdayStartHour, params.saturdayEndHour)) {
            DomainUtils.addError(userWorkingHours, "O horário de sábado inválido.")
        }

        if (!isValidWeekendHours(params.sundayStartHour, params.sundayEndHour)) {
            DomainUtils.addError(userWorkingHours, "O horário de domingo inválido.")
        }

        return userWorkingHours
    }

    private Map parseDefaultParams(Map params) {
        params.weekdaysStartHour = Utils.toInteger(params.weekdaysStartHour)
        params.weekdaysEndHour = Utils.toInteger(params.weekdaysEndHour)

        params.saturdayStartHour = Utils.toInteger(params.saturdayStartHour)
        params.saturdayEndHour = Utils.toInteger(params.saturdayEndHour)

        params.sundayStartHour = Utils.toInteger(params.sundayStartHour)
        params.sundayEndHour = Utils.toInteger(params.sundayEndHour)

        return params
    }

    private Boolean isValidWeekendHours(Integer startHour, Integer endHour) {
        if (startHour == null && endHour != null) return false
        if (startHour != null && endHour == null) return false
        if (startHour != null && endHour != null && !isValidHours(startHour, endHour)) return false
        return true
    }

    private Boolean isValidHours(Integer startHour, Integer endHour) {
        if (!CustomDateUtils.isValidHour(startHour)) return false
        if (!CustomDateUtils.isValidHour(endHour)) return false
        if (startHour == endHour) return false
        return true
    }
}
