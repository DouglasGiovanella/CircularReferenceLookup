package com.asaas.service.riskanalysis

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class RiskAnalysisFloatService {

    def customerStageService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void saveRiskAnalysisRequestIfNecessary(Payment payment) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.PAYMENT_VALUE_RECEIVED_ABOVE_LIMIT_WITHOUT_FLOAT
        if (!shouldSaveRiskAnalysisRequest(payment, riskAnalysisReason)) return

        riskAnalysisRequestService.saveAnalysisWithNewTransaction(payment.provider.id, riskAnalysisReason, payment.id)
    }

    public Boolean shouldSaveRiskAnalysisRequest(Payment payment, RiskAnalysisReason riskAnalysisReason) {
        if (payment.creditDate > new Date().clearTime()) return false

        if (payment.provider.accountOwner) return false

        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        Date customerConvertedDate = customerStageService.findConvertedDateWithFallback(payment.provider)
        if (customerConvertedDate && customerConvertedDate <= CustomDateUtils.sumYears(new Date(), -1)) return false

        Boolean customerHasAutomaticTransferEnabled = CustomerParameter.getValue(payment.provider, CustomerParameterName.AUTOMATIC_TRANSFER)
        if (customerHasAutomaticTransferEnabled) return false

        Boolean isPaymentValueAboveLimit = payment.value > getMaxValueWithoutPaymentFloat(payment.provider)
        if (!isPaymentValueAboveLimit) return false

        return true
    }

    private BigDecimal getMaxValueWithoutPaymentFloat(Customer customer) {
        BigDecimal customMaxValue = CustomerParameter.getNumericValue(customer, CustomerParameterName.MAX_VALUE_WITHOUT_PAYMENT_FLOAT)
        if (customMaxValue) return customMaxValue

        if (customer.isNaturalPerson()) return 1500.00
        if (customer.companyType?.isMEI() || customer.companyType?.isIndividual()) return 5000.00
        if (customer.companyType?.isLimited()) return 10000.00

        return 2000.00
    }
}
