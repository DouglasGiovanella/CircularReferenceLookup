package com.asaas.service.receivableanticipationanalysis

import com.asaas.billinginfo.BillingType
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysis
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalyst
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalysisStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAnalysisQueueService {

    def receivableAnticipationAnalysisHistoryService
    def receivableAnticipationAnalysisService

    public void distributeAnalysis() {
        List<Long> analystIdList = ReceivableAnticipationAnalyst.query([column: "id", isWorking: true]).list()

        for (Long analystId : analystIdList) {
            distributeForAnalyst(analystId)
        }
    }

    public void distributeForAnalyst(Long analystId) {
        Integer currentAnalysisCount = ReceivableAnticipationAnalysis.query([column: "id", analystId: analystId, "status[in]": ReceivableAnticipationAnalysisStatus.listAwaitingAnalysisStatus() ]).count()
        final Integer maximumAnalysisCount = 25
        if (currentAnalysisCount >= maximumAnalysisCount) return

        Integer remainingAnalysisCount = maximumAnalysisCount - currentAnalysisCount

        ReceivableAnticipationAnalyst analyst = ReceivableAnticipationAnalyst.get(analystId)
        List<Long> analysisIdList = listNextPendingAnalysis(analyst.billingTypeForAnalysis, remainingAnalysisCount)

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysis.get(analysisId)

                Map existingAnalysisSearch = [exists: true,
                                              "analystId[ne]": analystId,
                                              "analystId[isNotNull]": true,
                                              "status[in]": ReceivableAnticipationAnalysisStatus.listAwaitingAnalysisStatus(),
                                              "customerId": analysis.receivableAnticipation.customer.id]
                Boolean customerHasAnticipationInAnalysis = ReceivableAnticipationAnalysis.query(existingAnalysisSearch).get().asBoolean()
                if (customerHasAnticipationInAnalysis) return

                remainingAnalysisCount = updateAnalystForCustomerAnalysisList(analysis.receivableAnticipation.customer.id, analystId, analyst.billingTypeForAnalysis, remainingAnalysisCount)

                if (remainingAnalysisCount <= 0) return
            }, [ logErrorMessage: "ReceivableAnticipationAnalysisQueueService.distributeForAnalyst >> Ocorreu um erro ao distribuir a análise [${analysisId}] para o analista [${analystId}]"])
        }
    }

    public void returnAnalysisToQueue(Long analystId) {
        List<Long> analysisIdList = ReceivableAnticipationAnalysis.query([column: "id", analystId: analystId, "status[in]": ReceivableAnticipationAnalysisStatus.listAwaitingAnalysisStatus()]).list()

        for (Long analysisId : analysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysis.get(analysisId)

                if (!ReceivableAnticipationAnalysisStatus.listAwaitingAnalysisStatus().contains(analysis.status)) return

                analysis.analyst = null
                analysis.status = ReceivableAnticipationAnalysisStatus.PENDING
                analysis.save(failOnError: true)

                receivableAnticipationAnalysisHistoryService.save(analysis)
            }, [logErrorMessage: "ReceivableAnticipationAnalysisQueueService.returnAnalysisToQueue >> Ocorreu um erro ao devolver a análise [${analysisId}] do analista [${analystId}] para a fila"])
        }
    }

    private List<Long> listNextPendingAnalysis(BillingType billingTypeForAnalysis, Integer remainingAnalysisCount) {
        Map search = [
            column: "id",
            "analystId[isNull]": true,
            status: ReceivableAnticipationAnalysisStatus.PENDING,
            "receivableAnticipationStatus": ReceivableAnticipationStatus.PENDING,
            "nextAnalysis[le]": new Date(),
            limit: remainingAnalysisCount,
            order: "asc"
        ]

        if (billingTypeForAnalysis.isBoleto()) {
            search."receivableAnticipationBillingType[in]" = [BillingType.BOLETO, BillingType.PIX]
        } else {
            search.receivableAnticipationBillingType = billingTypeForAnalysis
        }

        List<Long> analysisIdList = ReceivableAnticipationAnalysis.query(search).list()

        return analysisIdList
    }

    private Integer updateAnalystForCustomerAnalysisList(Long customerId, Long analystId, BillingType billingTypeForAnalysis, Integer remainingAnalysisCount) {
        Map customerSearch = [
            column: "id",
            "analystId[isNull]": true,
            status: ReceivableAnticipationAnalysisStatus.PENDING,
            "nextAnalysis[le]": new Date(),
            "customerId": customerId,
            order: "asc"
        ]

        if (billingTypeForAnalysis.isBoleto()) {
            customerSearch."receivableAnticipationBillingType[in]" = [BillingType.BOLETO, BillingType.PIX]
        } else {
            customerSearch.receivableAnticipationBillingType = billingTypeForAnalysis
        }

        List<Long> customerAnalysisIdList = ReceivableAnticipationAnalysis.query(customerSearch).list()

        for (Long customerAnalysisId : customerAnalysisIdList) {
            receivableAnticipationAnalysisService.updateAnalyst(customerAnalysisId, analystId)
            remainingAnalysisCount--
        }

        return remainingAnalysisCount
    }
}
