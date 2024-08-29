package com.asaas.service.receivableanticipationanalysis

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysis
import com.asaas.exception.BusinessException
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.repository.ReceivableAnticipationRepository
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalysisStatus
import com.asaas.receivableanticipationanalysis.repository.ReceivableAnticipationAnalysisRepository
import com.asaas.receivableanticipationanalysis.repository.ReceivableAnticipationAnalystRepository
import com.asaas.service.receivableanticipation.ReceivableAnticipationHistoryService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class ReceivableAnticipationAnalysisService {

    ReceivableAnticipationAnalysisHistoryService receivableAnticipationAnalysisHistoryService
    ReceivableAnticipationHistoryService receivableAnticipationHistoryService

    public void createAnalysisForAnticipations() {
        final Integer maximumNumberOfAnticipationsPerProcess = 2000
        Map search = ["receivableAnticipationAnalysis[notExists]": true,
                      "lastUpdated[ge]": CustomDateUtils.sumHours(new Date(), -3),
                      status: ReceivableAnticipationStatus.PENDING
        ]

        List<Long> receivableAnticipationIdList = ReceivableAnticipationRepository.query(search)
            .disableRequiredFilters()
            .column("id")
            .sort("anticipationDate", "asc")
            .list(max: maximumNumberOfAnticipationsPerProcess) as List<Long>

        Utils.forEachWithFlushSession(receivableAnticipationIdList, 50, { Long receivableAnticipationId ->
            save(receivableAnticipationId)
        })
    }

    public void finishAnalysisIfExists(Long receivableAnticipationId) {
        ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysisRepository.query([receivableAnticipationId: receivableAnticipationId]).disableRequiredFilters().get()

        if (!analysis || analysis.status == ReceivableAnticipationAnalysisStatus.FINISHED) return

        analysis.status = ReceivableAnticipationAnalysisStatus.FINISHED
        analysis.save(failOnError: true)

        receivableAnticipationAnalysisHistoryService.save(analysis)
    }

    public void updateAnalyst(Long analysisId, Long analystId) {
        ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysisRepository.get(analysisId)
        analysis.analyst = ReceivableAnticipationAnalystRepository.get(analystId)
        analysis.save(failOnError: true)

        receivableAnticipationAnalysisHistoryService.save(analysis)
    }

    public ReceivableAnticipationAnalysis setAsInProgress(Long id) {
        ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysisRepository.get(id)

        if (analysis?.status != ReceivableAnticipationAnalysisStatus.PENDING) return analysis

        analysis.status = ReceivableAnticipationAnalysisStatus.IN_PROGRESS
        analysis.save(failOnError: true)

        receivableAnticipationAnalysisHistoryService.save(analysis)

        return analysis
    }

    public void postponeAnalysis(Long id, String observation) {
        ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysisRepository.get(id)
        if (!analysis.status.isAwaitingAnalysis() && !analysis.status.isAwaitingBatchPostpone()) throw new BusinessException("Situação da análise não permite ser postergada")

        receivableAnticipationHistoryService.save(analysis.receivableAnticipation, observation)

        analysis.nextAnalysis = CustomDateUtils.sumHours(new Date(), 6)
        analysis.status = ReceivableAnticipationAnalysisStatus.PENDING
        analysis.analyst = null
        analysis.save(failOnError: true)

        receivableAnticipationAnalysisHistoryService.save(analysis)
    }

    public ReceivableAnticipationAnalysis save(Long receivableAnticipationId) {
        ReceivableAnticipation receivableAnticipation = ReceivableAnticipationRepository.get(receivableAnticipationId)

        ReceivableAnticipationAnalysis receivableAnticipationAnalysis = new ReceivableAnticipationAnalysis()
        receivableAnticipationAnalysis.receivableAnticipation = receivableAnticipation
        receivableAnticipationAnalysis.customer = receivableAnticipation.customer
        receivableAnticipationAnalysis.save(failOnError: true)

        receivableAnticipationAnalysisHistoryService.save(receivableAnticipationAnalysis)

        return receivableAnticipationAnalysis
    }

    public void setAsError(Long receivableAnticipationAnalysisId) {
        ReceivableAnticipationAnalysis receivableAnticipationAnalysis = ReceivableAnticipationAnalysisRepository.get(receivableAnticipationAnalysisId)

        receivableAnticipationAnalysis.status = ReceivableAnticipationAnalysisStatus.BATCH_ITEM_ERROR
        receivableAnticipationAnalysis.save(failOnError: true)
    }
}
