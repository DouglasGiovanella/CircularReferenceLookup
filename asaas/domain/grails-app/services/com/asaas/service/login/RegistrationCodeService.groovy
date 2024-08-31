package com.asaas.service.login

import com.asaas.domain.login.RegistrationCode
import com.asaas.domain.user.User
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional

@Transactional
class RegistrationCodeService {

    public static final Integer TOKEN_EXPIRATION_DAYS = 2

    def asaasSegmentioService
    def grailsLinkGenerator

    public String buildResetPasswordUrl(User user) {
        String usernameFieldName = SpringSecurityUtils.securityConfig.userLookup.usernamePropertyName
        String token = buildRegistrationCode(user."$usernameFieldName")

        return grailsLinkGenerator.link(controller: 'register', action: 'resetPassword', params: [t: token], absolute: true)
    }

    public RegistrationCode validate(RegistrationCode registrationCode) {
        RegistrationCode validatedRegistrationCode = new RegistrationCode()
        Date tokenExpirationDate = CustomDateUtils.sumDays(new Date(), -TOKEN_EXPIRATION_DAYS, true)

        Boolean registrationCodeExpired = registrationCode?.dateCreated < tokenExpirationDate

        if (registrationCode) {
            Long userId = User.query([column: "id", username: registrationCode?.username, ignoreCustomer:true]).get()
            if (userId) {
                asaasSegmentioService.track(userId, "third_party_access_depreciation", [action: "reset password open", linkExpired: registrationCodeExpired])
            } else {
                DomainUtils.addError(validatedRegistrationCode, Utils.getMessageProperty("security.resetPassword.userNotFound"))
            }
        }

        if (!registrationCode || registrationCodeExpired) {
            DomainUtils.addError(validatedRegistrationCode, Utils.getMessageProperty("security.resetPassword.invalidCode"))
        }

        return validatedRegistrationCode
    }

    public void deleteAllNotExpired(String username) {
        Date tokenExpirationDate = CustomDateUtils.sumDays(new Date(), -TOKEN_EXPIRATION_DAYS, true)
        RegistrationCode.executeUpdate("delete from RegistrationCode rc where rc.username = :username and rc.dateCreated >= :tokenExpirationDate", [username: username, tokenExpirationDate: tokenExpirationDate])
    }

    public RegistrationCode findByToken(String token) {
        if (!token) return null

        String encodedToken = token.encodeAsSHA256()

        RegistrationCode registrationCode = RegistrationCode.findByToken(encodedToken)
        return registrationCode
    }

    private String buildRegistrationCode(String username) {
        RegistrationCode registrationCode = new RegistrationCode(username: username)
        String originalToken = registrationCode.token
        registrationCode.token = registrationCode.token.encodeAsSHA256()
        registrationCode.save(failOnError: true)

        return originalToken
    }
}
