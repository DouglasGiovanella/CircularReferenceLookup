package com.asaas.service.riskanalysis

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.customer.Customer
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RiskAnalysisChargebackService {

    def asyncActionService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void createRiskAnalysisChargeback() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.FIRST_CHARGEBACK_RECEIVED
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.RISK_ANALYSIS_CHARGEBACK, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                Chargeback chargeback = Chargeback.get(asyncActionData.chargebackId)
                riskAnalysisRequestService.save(customer, riskAnalysisReason, [domainObject: chargeback])
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "RiskAnalysisChargebackService.createRiskAnalysisChargeback >> Erro ao salvar análise de risco de conta com chargeback. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void saveAsyncActionIfNecessary(Customer customer, Long chargebackId) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.FIRST_CHARGEBACK_RECEIVED
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        Boolean hasChargebackAnalysisVerified = RiskAnalysisRequest.hasChargebackRiskAnalysisVerified([customer: customer]).get().asBoolean()

        if (hasChargebackAnalysisVerified) return

        AsyncActionType asyncActionType = AsyncActionType.RISK_ANALYSIS_CHARGEBACK
        Map asyncActionData = [customerId: customer.id, chargebackId: chargebackId]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }
}
