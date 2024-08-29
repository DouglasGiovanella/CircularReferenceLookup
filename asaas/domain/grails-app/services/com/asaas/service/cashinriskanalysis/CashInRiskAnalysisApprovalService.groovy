package com.asaas.service.cashinriskanalysis

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.cashinriskanalysis.CashInRiskAnalysisRequestFinishReason
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequestReason
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class CashInRiskAnalysisApprovalService {

    def pixCreditTransactionAuthorizationService

    public void approveAutomatically(CashInRiskAnalysisRequest analysis, CashInRiskAnalysisRequestFinishReason reason, String observation) {
        analysis.status = AnalysisRequestStatus.APPROVED
        analysis.analysisDate = new Date()
        analysis.observations = "Aprovada automaticamente pelo sistema."
        if (observation) {
            analysis.observations += System.lineSeparator() + observation
        }
        analysis.finishReason = reason
        analysis.save(failOnError: true)

        approveTransactions(analysis)
    }

    public void approveTransactions(CashInRiskAnalysisRequest analysis) {
        List<CashInRiskAnalysisRequestReason> analysisRequestReasonList = analysis.getCashInRiskAnalysisRequestReasonList()
        for (CashInRiskAnalysisRequestReason analysisRequestReason : analysisRequestReasonList) {
            if (analysisRequestReason.pixTransaction) {
                if (analysisRequestReason.pixTransaction.payment.status.isRefunded()) {
                    AsaasLogger.info("CashInRiskAnalysisApprovalService.approveTransactions >> Bloqueio cautelar analisado como aprovado, porém a cobrança já foi estornada pelo cliente [paymentId: ${analysisRequestReason.pixTransaction.paymentId}, customerId: ${analysisRequestReason.pixTransaction.payment.providerId}]")
                    continue
                }
                pixCreditTransactionAuthorizationService.onCashInRiskAnalysisRequestApproved(analysisRequestReason.pixTransaction)
            }
        }
    }
}
