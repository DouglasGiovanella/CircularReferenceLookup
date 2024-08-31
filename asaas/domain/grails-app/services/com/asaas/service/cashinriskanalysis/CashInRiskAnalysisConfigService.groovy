package com.asaas.service.cashinriskanalysis

import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CashInRiskAnalysisConfigService {

    def cashInRiskAnalysisConfigCacheService

    public void updateConfigProperty(String property, String value) {
        BusinessValidation businessValidation = validateUpdateConfigProperty(property)
        if (!businessValidation.isValid()) {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }

        Object parsedValue = parseValue(property, value)

        CashInRiskAnalysisConfig cashInRiskAnalysisConfig = CashInRiskAnalysisConfig.getInstance()
        cashInRiskAnalysisConfig[property] = parsedValue
        cashInRiskAnalysisConfig.save(failOnError: true)

        cashInRiskAnalysisConfigCacheService.evict()
    }

    private Object parseValue(String property, String value) {
        switch (property) {
            case "maxAllowedCountCreditPixTransactions":
            case "daysToEnableCustomerAnalysis":
            case "maxAllowedPixConfirmedFraudForSmallCustomer":
            case "maxAllowedPixConfirmedFraudForBussinessCustomer":
            case "maxAllowedPixConfirmedFraudForCorporateCustomer":
                return Utils.toInteger(value)
            case "maxAllowedValueForSmallCustomer":
            case "maxAllowedValueForBusinessCustomer":
                return Utils.toBigDecimal(value)
            case "maxAllowedInfractionsInLastSemester":
            case "maxAllowedInfractionsWithFraudDetectedInLastSemester":
                return Utils.toLong(value)
            case "enabled":
                if (value.toLowerCase() == "false") return false
                if (value.toLowerCase() == "true") return true
            default:
                return null
        }
    }

    private BusinessValidation validateUpdateConfigProperty(String property) {
        BusinessValidation businessValidation = new BusinessValidation()

        List<String> updatableProperties = CashInRiskAnalysisConfig.listUpdatableProperties()

        if (!updatableProperties.contains(property)) {
            businessValidation.addError("riskAnalysisConfig.error.notUpdatableProperty")
        }
        return businessValidation
    }
}
