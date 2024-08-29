package com.asaas.service.loginlinkvalidationrequest

import com.asaas.cache.loginlinkvalidationrequest.LoginLinkValidationRequestConfigCacheVO
import com.asaas.domain.loginlinkvalidationrequest.LoginLinkValidationRequestConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class LoginLinkValidationRequestConfigService {

    def loginLinkValidationRequestConfigCacheService

    public void updateConfigProperty(String property, String value) {
        BusinessValidation businessValidation = validateUpdateConfigProperty(property)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        Object parsedValue = prepareValue(property, value)
        if (property == "percentEnabledEmail" || property == "percentEnabledSms") {
            property = "percentEnabledValues"
        }

        LoginLinkValidationRequestConfig loginLinkValidationRequestConfig = LoginLinkValidationRequestConfig.getInstance()
        loginLinkValidationRequestConfig[property] = parsedValue
        loginLinkValidationRequestConfig.save(failOnError: true)

        loginLinkValidationRequestConfigCacheService.evict()
    }

    private Object prepareValue(String property, String value) {
        switch (property) {
            case "enabled":
            case "enabledValidation":
                if (value.toLowerCase() == "false") return false
                if (value.toLowerCase() == "true") return true
            case "percentEnabledEmail":
            case "percentEnabledSms":
                return preparePercentEnabledValues(property, value)
            case "expirationTimeInMinutes":
                return Utils.toInteger(value)
            default:
                return null
        }
    }

    private BusinessValidation validateUpdateConfigProperty(String property) {
        BusinessValidation businessValidation = new BusinessValidation()

        List<String> updatableProperties = LoginLinkValidationRequestConfig.listUpdatableProperties()

        if (!updatableProperties.contains(property)) {
            businessValidation.addError("loginLinkValidationRequestConfig.error.notUpdatableProperty")
        }
        return businessValidation
    }

    private String preparePercentEnabledValues(String property, String value) {
        LoginLinkValidationRequestConfigCacheVO loginLinkValidationRequestConfigCacheVO = loginLinkValidationRequestConfigCacheService.getInstance()

        Map propertyParams = [:]
        propertyParams.percentEnabledSms = loginLinkValidationRequestConfigCacheVO.percentEnabledSms
        propertyParams.percentEnabledEmail = loginLinkValidationRequestConfigCacheVO.percentEnabledEmail

        propertyParams[property] = value

        return JsonOutput.toJson(propertyParams).toString()
    }
}
