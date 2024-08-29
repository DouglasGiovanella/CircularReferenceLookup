package com.asaas.service.cashinriskanalysis

import com.asaas.cache.cashinriskanalysis.CashInRiskAnalysisConfigCacheVO
import com.asaas.cashinriskanalysis.CashInRiskAnalysisTriggeredRule
import com.asaas.cashinriskanalysis.vo.CashInRiskAnalysisCheckResultVO
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransactionExternalAccount
import grails.transaction.Transactional

@Transactional
class CashInRiskAnalysisService {

    def cashInRiskAnalysisConfigCacheService
    def customerPixFraudStatisticsCacheService

    public CashInRiskAnalysisCheckResultVO checkIfCashInNeedsPrecautionaryBlock(Payment payment) {
        CashInRiskAnalysisConfigCacheVO cashInRiskAnalysisConfig = cashInRiskAnalysisConfigCacheService.getInstance()
        if (!cashInRiskAnalysisConfig.enabled) return new CashInRiskAnalysisCheckResultVO(false)

        Customer customer = payment.provider

        if (customer.isLegalPerson()) return new CashInRiskAnalysisCheckResultVO(false)

        Boolean hasAnalysedCashInRiskAnalysisRequest = CashInRiskAnalysisRequest.query([exists: true, customer: customer, analyzed: true]).get().asBoolean()
        if (hasAnalysedCashInRiskAnalysisRequest) return new CashInRiskAnalysisCheckResultVO(false)

        String payerCpfCnpj = PixTransactionExternalAccount.query([column: "cpfCnpj", payment: payment, disableSort: true]).get()
        if (customer.cpfCnpj == payerCpfCnpj) return new CashInRiskAnalysisCheckResultVO(false)

        if (isPixConfirmedFraudAboveMaxAllowedForCustomerSegment(customer, cashInRiskAnalysisConfig)) {
            return new CashInRiskAnalysisCheckResultVO(true, CashInRiskAnalysisTriggeredRule.PIX_CONFIRMED_FRAUD_ABOVE_MAX_ALLOWED_FOR_CUSTOMER_SEGMENT)
        }

        return new CashInRiskAnalysisCheckResultVO(false)
    }

    private Boolean isPixConfirmedFraudAboveMaxAllowedForCustomerSegment(Customer customer, CashInRiskAnalysisConfigCacheVO cashInRiskAnalysisConfig) {
        Integer totalAccountFraudConfirmed = customerPixFraudStatisticsCacheService.getTotalAccountFraudConfirmedInLastOneYear(customer.id)
        if (!totalAccountFraudConfirmed) return false

        if (customer.segment.isSmall()) return totalAccountFraudConfirmed > cashInRiskAnalysisConfig.maxAllowedPixConfirmedFraudForSmallCustomer
        if (customer.segment.isBusiness()) return totalAccountFraudConfirmed > cashInRiskAnalysisConfig.maxAllowedPixConfirmedFraudForBussinessCustomer
        if (customer.segment.isCorporate()) return totalAccountFraudConfirmed > cashInRiskAnalysisConfig.maxAllowedPixConfirmedFraudForCorporateCustomer

        return false
    }
}
