package com.asaas.service.mfa

import com.asaas.domain.user.User
import com.asaas.domain.user.UserMultiFactorAuth
import com.asaas.domain.user.UserMultiFactorDevice
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.login.MfaType
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import org.springframework.web.context.request.RequestContextHolder

@Transactional
class MfaAdminService {

    def adminAccessTrackingService
    def connectedAccountInfoHandlerService
    def userMultiFactorDeviceService

    public String getMfaCode(Long userMultiFactorAuthId) {
        User currentUser = UserUtils.getCurrentUser()
        if (!AdminUserPermissionUtils.canViewAuthorizationToken(currentUser)) {
            throw new BusinessException("Usuário sem autorização para visualizar o token")
        }

        UserMultiFactorAuth userMultiFactorAuth = UserMultiFactorAuth.get(userMultiFactorAuthId)
        if (!userMultiFactorAuth) throw new ResourceNotFoundException("Token não encontrado")

        if (userMultiFactorAuth.isExpired()) {
            throw new BusinessException("Token expirado")
        }

        String mfaSmsToken = userMultiFactorAuth.getDecryptedCode()

        Long customerId = userMultiFactorAuth.user.customer.id
        adminAccessTrackingService.save(RequestContextHolder.requestAttributes.params, userMultiFactorAuthId.toString(), customerId)

        return mfaSmsToken
    }

    public void registerUserEmailMfa(User user) {
        BusinessValidation businessValidation = canChangeUserMfaTypeToEmail(user)

        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        userMultiFactorDeviceService.registerEmail(user)
        adminAccessTrackingService.save(RequestContextHolder.requestAttributes.params, user.id.toString(), user.customer.id)
    }

    public void registerUserSmsMfa(User user, String mobilePhone) {
        String mfaPhoneNumber = PhoneNumberUtils.sanitizeNumber(mobilePhone)
        BusinessValidation businessValidation = canChangeUserMfaTypeToSms(user, mfaPhoneNumber)

        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        user.mobilePhone = mfaPhoneNumber
        user.save(failOnError: true)

        userMultiFactorDeviceService.registerSMS(user, mfaPhoneNumber)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(user))
        adminAccessTrackingService.save(RequestContextHolder.requestAttributes.params, user.id.toString(), user.customer.id)
    }

    private BusinessValidation canChangeUserMfaTypeToEmail(User user) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (user.sysAdmin()) {
            validatedBusiness.addError("userAdmin.mfa.cantChangeUserMfaType")
            return validatedBusiness
        }

        Boolean hasEmailAsMfaType = UserMultiFactorDevice.query([exists: true, mfaType: MfaType.EMAIL, user: user]).get().asBoolean()
        if (hasEmailAsMfaType) {
            validatedBusiness.addError("userAdmin.mfa.emailTypeAlreadyInUse")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private BusinessValidation canChangeUserMfaTypeToSms(User user, String mobilePhone) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (user.sysAdmin()) {
            validatedBusiness.addError("userAdmin.mfa.cantChangeUserMfaType")
            return validatedBusiness
        }

        if (!PhoneNumberUtils.validateMobilePhone(mobilePhone)) {
            validatedBusiness.addError("userAdmin.mfa.smsTypeInvalidMobilePhone")
            return validatedBusiness
        }

        Boolean isMobilePhoneAlreadyInUse = UserMultiFactorDevice.query([exists: true, user: user, mobilePhone: mobilePhone]).get().asBoolean()
        if (isMobilePhoneAlreadyInUse) {
            validatedBusiness.addError("userAdmin.mfa.smsTypeMobilePhoneAlreadyInUse")
            return validatedBusiness
        }

        return validatedBusiness
    }
}
