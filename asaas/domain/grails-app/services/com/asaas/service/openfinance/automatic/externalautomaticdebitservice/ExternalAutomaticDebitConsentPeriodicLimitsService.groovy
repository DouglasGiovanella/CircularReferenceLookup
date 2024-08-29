package com.asaas.service.openfinance.automatic.externalautomaticdebitservice

import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentPeriodicLimits
import com.asaas.openfinance.automatic.externalautomaticdebitconsent.adapter.children.PeriodicLimitsAdapter

import grails.transaction.Transactional

@Transactional
class ExternalAutomaticDebitConsentPeriodicLimitsService {

    public ExternalAutomaticDebitConsentPeriodicLimits save(PeriodicLimitsAdapter periodicLimitsAdapter, ExternalAutomaticDebitConsentInfo externalAutomaticDebitConsentInfo) {
        ExternalAutomaticDebitConsentPeriodicLimits periodicLimits = new ExternalAutomaticDebitConsentPeriodicLimits()
        periodicLimits.externalAutomaticDebitConsentInfo = externalAutomaticDebitConsentInfo
        periodicLimits.dailyTransactionLimitValue = periodicLimitsAdapter.dailyTransactionLimitValue
        periodicLimits.dailyTransactionLimitQuantity = periodicLimitsAdapter.dailyTransactionLimitQuantity
        periodicLimits.weeklyTransactionLimitValue = periodicLimitsAdapter.weeklyTransactionLimitValue
        periodicLimits.weeklyTransactionLimitQuantity = periodicLimitsAdapter.weeklyTransactionLimitQuantity
        periodicLimits.monthlyTransactionLimitValue = periodicLimitsAdapter.monthlyTransactionLimitValue
        periodicLimits.monthlyTransactionLimitQuantity = periodicLimitsAdapter.monthlyTransactionLimitQuantity
        periodicLimits.yearlyTransactionLimitValue = periodicLimitsAdapter.yearlyTransactionLimitValue
        periodicLimits.yearlyTransactionLimitQuantity = periodicLimitsAdapter.yearlyTransactionLimitQuantity
        periodicLimits.save(failOnError: true)

        return periodicLimits
    }

}
