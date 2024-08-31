package com.asaas.service.riskanalysis

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.riskAnalysis.RiskAnalysisRequest
import com.asaas.log.AsaasLogger
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RiskAnalysisPaymentService {

    def asyncActionService
    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void analyzeInstallmentIfNecessary(Installment installment) {
        if (mustSendPaymentToRiskAnalysis(installment.description)) saveAsyncActionIfNecessary(null, installment.id)
    }

    public void analyzePaymentIfNecessary(Payment payment) {
        if (payment.installment) return
        if (mustSendPaymentToRiskAnalysis(payment.description)) saveAsyncActionIfNecessary(payment.id, null)
    }

    public void createToAnalyzeSuspectPaymentDescription() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.SUSPECT_PAYMENT_DESCRIPTION
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer maxItemsPerCycle = 500
        List<Map> riskAnalysisPaymentDescriptionList = asyncActionService.listPending(AsyncActionType.RISK_ANALYSIS_SUSPECT_PAYMENT_DESCRIPTION, maxItemsPerCycle)

        if (!riskAnalysisPaymentDescriptionList) return

        final Integer flushEvery = 50

        Utils.forEachWithFlushSession(riskAnalysisPaymentDescriptionList, flushEvery, { Map riskAnalysisPaymentDescriptionInfo ->
            Utils.withNewTransactionAndRollbackOnError({
                Payment paymentToRiskAnalysis

                if (riskAnalysisPaymentDescriptionInfo.paymentId) {
                    paymentToRiskAnalysis = Payment.read(riskAnalysisPaymentDescriptionInfo.paymentId)
                } else {
                    paymentToRiskAnalysis = Payment.query([installmentId: riskAnalysisPaymentDescriptionInfo.installmentId, sort: "id", order: "asc"]).get()
                }

                if (paymentToRiskAnalysis && mustRegisterPaymentForRiskAnalysis(paymentToRiskAnalysis)) {
                    riskAnalysisRequestService.save(paymentToRiskAnalysis.provider, riskAnalysisReason, [domainObject: paymentToRiskAnalysis])

                    String riskAnalysisLogMessage = "RiskAnalysisPaymentService.createToAnalyzeSuspectPaymentDescription >> Análise de risco gerada para a cobrança [ID: ${paymentToRiskAnalysis.id}]"
                    if (riskAnalysisPaymentDescriptionInfo.installmentId) riskAnalysisLogMessage += " / Parcelamento [ID: ${riskAnalysisPaymentDescriptionInfo.installmentId}]"

                    AsaasLogger.info(riskAnalysisLogMessage)
                }

                asyncActionService.delete(riskAnalysisPaymentDescriptionInfo.asyncActionId)
            }, [logErrorMessage: "RiskAnalysisPaymentService.createToAnalyzeSuspectPaymentDescription >> Ocorreu uma falha ao realizar análise de risco para o item [ID: ${riskAnalysisPaymentDescriptionInfo.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(riskAnalysisPaymentDescriptionInfo.asyncActionId) }
            ])
        })
    }

    private void saveAsyncActionIfNecessary(Long paymentId, Long installmentId) {
        AsyncActionType asyncActionType = AsyncActionType.RISK_ANALYSIS_SUSPECT_PAYMENT_DESCRIPTION
        Map asyncActionData = [paymentId: paymentId, installmentId: installmentId]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    private Boolean mustRegisterPaymentForRiskAnalysis(Payment payment) {
        final Date minimumDateCreatedToRiskAnalysis = CustomDateUtils.sumMinutes(new Date(), -30)
        if (payment.dateCreated < minimumDateCreatedToRiskAnalysis) return false

        Customer customer = payment.provider

        if (customer.suspectedOfFraud != null) return false
        if (customer.status.isInactive()) return false
        if (customer.companyType?.isLimited()) return false
        if (customer.segment?.isCorporate()) return false
        if (customer.customerRegisterStatus.generalApproval.isRejected()) return false
        if (hasRecentCustomerRiskAnalysisRequestReason(customer)) return false

        return true
    }

    private Boolean mustSendPaymentToRiskAnalysis(String description) {
        if (!description) return false

        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.SUSPECT_PAYMENT_DESCRIPTION
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        description = description.toLowerCase()

        final List<Map> suspectTermList = [
            [term: "arrecadação"],
            [term: "banco", excludeList: ["banco do brasil", "banco emissor", "pagável em banco", "pagável em qualquer banco", "banco de dados"]],
            [term: "campanha"],
            [term: "consórcio"],
            [term: "dívida"],
            [term: "eleições"],
            [term: "eleitorais"],
            [term: "eleitoral"],
            [term: "empréstimo"],
            [term: "financeira", excludeList: ["consultoria", "assessoria"]],
            [term: "financiamento"],
            [term: "fundo"],
            [term: "leilão"],
            [term: "limpa nome"],
            [term: "lote"],
            [term: "loteamento"],
            [term: "negociação"],
            [term: "partidário"],
            [term: "partido"],
            [term: "quitação", excludeList: ["consultoria", "assessoria", "curso"]],
            [term: "rifa", excludeList: ["tarifa"]],
            [term: "serasa"],
            [term: "sorteio"],
            [term: "terreno"]
        ]

        Boolean mustSendToAnalysis = suspectTermList.any { Map suspectTerm ->
            Boolean hasSuspectTerm = description.contains(suspectTerm.term)
            if (!hasSuspectTerm) return false

            Boolean hasExcludeTerm = suspectTerm.excludeList.any { description.contains(it) }
            if (hasExcludeTerm) return false

            return true
        }

        return mustSendToAnalysis
    }

    private Boolean hasRecentCustomerRiskAnalysisRequestReason(Customer customer) {
        final Integer daysToRecentRiskAnalysis = 30
        final Map search = [exists: true, customer: customer, "analysisDate[ge]": CustomDateUtils.sumDays(new Date(), daysToRecentRiskAnalysis * -1), reason: RiskAnalysisReason.SUSPECT_PAYMENT_DESCRIPTION]
        return RiskAnalysisRequest.query(search).get().asBoolean()
    }
}
