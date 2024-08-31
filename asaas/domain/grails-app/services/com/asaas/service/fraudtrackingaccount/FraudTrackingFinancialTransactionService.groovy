package com.asaas.service.fraudtrackingaccount

import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.integration.sauron.adapter.fraudtracking.FraudTrackingFinancialTransactionAdapter
import grails.transaction.Transactional

@Transactional
class FraudTrackingFinancialTransactionService {

    def asaasSegmentioService

    public List<Long> getFinancialTransactionIdList(Long lastQueriedId, Integer limit) {
        final Integer maxLimit = 1000

        if (limit > maxLimit) limit = maxLimit
        List<Long> financialTransactionIdList = FinancialTransaction.query([column: "id", transactionTypeList: FinancialTransactionType.getFraudTrackingTransactionTypeList(), "id[gt]": lastQueriedId]).list(max: limit)

        return financialTransactionIdList
    }

    public FraudTrackingFinancialTransactionAdapter buildFinancialTransacitonData(Long financialTransactionId) {
        FinancialTransaction financialTransaction = FinancialTransaction.read(financialTransactionId)
        FraudTrackingFinancialTransactionAdapter financialTransactionAdapter = new FraudTrackingFinancialTransactionAdapter(financialTransaction)

        trackFallbackBankSlipPayerInfoIfNecessary(financialTransaction.paymentId, financialTransactionAdapter)

        return financialTransactionAdapter
    }

    private void trackFallbackBankSlipPayerInfoIfNecessary(Long paymentId, FraudTrackingFinancialTransactionAdapter adapter) {
        if (!adapter.bankSlipPayerInfoOrigin) return

        if (adapter.bankSlipPayerInfoOrigin.isBankSlipPayerInfo()) return

        final String eventName = "payment_fallback_bank_slip_payer_info"

        Map trackInfo = [:]
        trackInfo.paymentId = paymentId
        trackInfo.action = "save"
        trackInfo.bankSlipPayerInfoOrigin = adapter.bankSlipPayerInfoOrigin.toString()

        asaasSegmentioService.track(adapter.customerId, eventName, trackInfo)
    }
}
