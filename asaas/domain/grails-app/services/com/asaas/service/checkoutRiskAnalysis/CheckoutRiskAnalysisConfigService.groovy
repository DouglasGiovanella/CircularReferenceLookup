package com.asaas.service.checkoutRiskAnalysis

import com.asaas.checkoutRiskAnalysis.CheckoutRiskAnalysisReason
import com.asaas.domain.checkoutRiskAnalysis.CheckoutRiskAnalysisConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CheckoutRiskAnalysisConfigService {

    def checkoutRiskAnalysisConfigCacheService

    public void updateConfigProperty(CheckoutRiskAnalysisReason checkoutRiskAnalysisReason, String property, String value) {
        BusinessValidation businessValidation = validateUpdateConfigProperty(property)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        def parsedValue = parseValue(property, value)

        CheckoutRiskAnalysisConfig checkoutRiskAnalysisConfig = CheckoutRiskAnalysisConfig.query([checkoutRiskAnalysisReason: checkoutRiskAnalysisReason]).get()
        checkoutRiskAnalysisConfig[property] = parsedValue
        checkoutRiskAnalysisConfig.save(failOnError: true)

        checkoutRiskAnalysisConfigCacheService.evict()
    }

    private Object parseValue(String property, def String value) {
        switch (property) {
            case "hoursToCheckUntrustedDeviceTransactions":
            case "delayMinutesToAutomaticAnalysis":
                return Utils.toInteger(value)
            case "maxValueToAutomaticallyApprove":
                return Utils.toBigDecimal(value)
            case "enabled":
                if (value.toLowerCase() == "false") return false
                if (value.toLowerCase() == "true") return true
            default:
                return null
        }
    }

    private BusinessValidation validateUpdateConfigProperty(String property) {
        BusinessValidation businessValidation = new BusinessValidation()

        List<String> updatableProperties = CheckoutRiskAnalysisConfig.listUpdatableProperties()

        if (!updatableProperties.contains(property)) {
            businessValidation.addError("riskAnalysisConfig.error.notUpdatableProperty")
        }
        return businessValidation
    }
}
