package com.asaas.service.cashinriskanalysis

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.cashinriskanalysis.CashInRiskAnalysisReason
import com.asaas.cashinriskanalysis.CashInRiskAnalysisTriggeredRule
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequest
import com.asaas.domain.cashinriskanalysis.CashInRiskAnalysisRequestReason
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class CreateCashInRiskAnalysisRequestService {

    def pixTransactionService

    public void saveForPrecautionaryBlock(Payment payment, CashInRiskAnalysisTriggeredRule triggeredRule) {
        CashInRiskAnalysisRequest cashInRiskAnalysisRequest = CashInRiskAnalysisRequest.query([status: AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED, customer: payment.provider]).get()

        if (!cashInRiskAnalysisRequest) {
            cashInRiskAnalysisRequest = new CashInRiskAnalysisRequest()
            cashInRiskAnalysisRequest.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED
            cashInRiskAnalysisRequest.customer = payment.provider
            cashInRiskAnalysisRequest.score = CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.score
            cashInRiskAnalysisRequest.expirationDate = buildExpirationDate()
        } else {
            cashInRiskAnalysisRequest.score += CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK.score
        }

        cashInRiskAnalysisRequest.save(failOnError: true)

        PixTransaction pixTransaction = PixTransaction.query([payment: payment]).get()

        CashInRiskAnalysisRequestReason cashInRiskAnalysisRequestReason = new CashInRiskAnalysisRequestReason()
        cashInRiskAnalysisRequestReason.cashInRiskAnalysisReason = CashInRiskAnalysisReason.PRECAUTIONARY_BLOCK
        cashInRiskAnalysisRequestReason.cashInRiskAnalysisRequest = cashInRiskAnalysisRequest
        cashInRiskAnalysisRequestReason.pixTransaction = pixTransaction
        cashInRiskAnalysisRequestReason.triggeredRule = triggeredRule
        cashInRiskAnalysisRequestReason.save(failOnError: true)

        pixTransactionService.awaitCashInRiskAnalysis(pixTransaction)
    }

    private Date buildExpirationDate() {
        Date dateToExpire = CustomDateUtils.sumHours(new Date(), CashInRiskAnalysisRequest.PIX_ANALYSIS_LIMIT_TIME_IN_HOURS)

        return CustomDateUtils.sumMinutes(dateToExpire, -CashInRiskAnalysisRequest.PIX_ANALYSIS_SAFE_MARGIN_TO_EXPIRE_IN_MINUTES)
    }
}
