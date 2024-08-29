package com.asaas.service.login

import com.asaas.domain.login.LoginConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class LoginConfigService {

    def loginConfigCacheService

    public void updateConfigProperty(String property, String value) {
        BusinessValidation businessValidation = validateUpdateConfigProperty(property)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        Object parsedValue = prepareValue(property, value)

        LoginConfig loginConfig = LoginConfig.getInstance()
        loginConfig[property] = parsedValue
        loginConfig.save(failOnError: true)

        loginConfigCacheService.evictThirdPartyLoginValidationConfig()
    }

    private Object prepareValue(String property, String value) {
        switch (property) {
            case "enabledThirdPartyLoginValidation":
                return Utils.toBoolean(value)
            case "minimumAuthScore":
                return buildMinimumAuthScore(value)
            default:
                return null
        }
    }

    private BusinessValidation validateUpdateConfigProperty(String property) {
        BusinessValidation businessValidation = new BusinessValidation()

        List<String> updatableProperties = LoginConfig.listUpdatableProperties()

        if (!updatableProperties.contains(property)) {
            businessValidation.addError("loginConfig.error.notUpdatableProperty")
        }
        return businessValidation
    }

    private String buildMinimumAuthScore(String value) {
        if (!StringUtils.isNumeric(value)) throw new BusinessException("O valor da propriedade precisa ser numérico.")

        return value
    }
}
