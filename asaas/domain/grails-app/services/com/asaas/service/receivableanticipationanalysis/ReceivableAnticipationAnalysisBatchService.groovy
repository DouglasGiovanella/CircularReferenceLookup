package com.asaas.service.receivableanticipationanalysis

import com.asaas.billinginfo.BillingType
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysis
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationBatchAnalysis
import com.asaas.receivableanticipation.ReceivableAnticipationDenialReason
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalysisStatus
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAnalysisBatchService {

    def receivableAnticipationAnalysisService
    def receivableAnticipationNonAutomaticApprovalRuleBypassService
    def receivableAnticipationService
    def sessionFactory

    public void sendAnalysisToBatchApprovalProcess(Long customerId, String observation) {
        final Integer maxNumberOfAnalysisToBeUpdatedPerCycle = 1000

        List<Long> receivableAnticipationAnalysisIdList = listReceivableAnticipationAnalysisAwaitingAnalysis(customerId, BillingType.MUNDIPAGG_CIELO)

        ReceivableAnticipationBatchAnalysis receivableAnticipationBatchAnalysis = saveBatch(observation, null)

        for (List<Long> idList : receivableAnticipationAnalysisIdList.collate(maxNumberOfAnalysisToBeUpdatedPerCycle)) {
            String sql = "update receivable_anticipation_analysis ra force index (primary) set ra.status = :status, ra.batch_id = :batch_id, last_updated = :lastUpdated, ra.version = version + 1 where ra.id in (:idList)"

            def query = sessionFactory.currentSession.createSQLQuery(sql)
            query.setTimestamp("lastUpdated", new Date())
            query.setParameterList("idList", idList)
            query.setString("status", ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_APPROVAL.toString())
            query.setLong("batch_id", receivableAnticipationBatchAnalysis.id)
            query.executeUpdate()
        }
    }

    public void sendAnalysisToBatchDenialProcess(Long customerId, ReceivableAnticipationDenialReason denialReason, String observation) {
        final Integer maxNumberOfAnalysisToBeUpdatedPerCycle = 1000

        List<Long> receivableAnticipationAnalysisIdList = listReceivableAnticipationAnalysisAwaitingAnalysis(customerId, BillingType.MUNDIPAGG_CIELO)

        ReceivableAnticipationBatchAnalysis receivableAnticipationBatchAnalysis = saveBatch(observation, denialReason)

        for (List<Long> idList : receivableAnticipationAnalysisIdList.collate(maxNumberOfAnalysisToBeUpdatedPerCycle)) {
            String sql = "update receivable_anticipation_analysis ra force index (primary) set ra.status = :status, ra.batch_id = :batch_id, last_updated = :lastUpdated, ra.version = version + 1 where ra.id in (:idList)"

            def query = sessionFactory.currentSession.createSQLQuery(sql)
            query.setTimestamp("lastUpdated", new Date())
            query.setParameterList("idList", idList)
            query.setString("status", ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_DENIAL.toString())
            query.setLong("batch_id", receivableAnticipationBatchAnalysis.id)
            query.executeUpdate()
        }
    }

    public void sendAnalysisToBatchPostponeProcess(Long customerId, BillingType billingType, String observation) {
        final Integer maxNumberOfAnalysisToBeUpdatedPerCycle = 1000

        List<Long> receivableAnticipationAnalysisIdList = listReceivableAnticipationAnalysisAwaitingAnalysis(customerId, billingType)

        ReceivableAnticipationBatchAnalysis receivableAnticipationBatchAnalysis = saveBatch(observation, null)

        for (List<Long> idList : receivableAnticipationAnalysisIdList.collate(maxNumberOfAnalysisToBeUpdatedPerCycle)) {
            String sql = "update receivable_anticipation_analysis ra force index (primary) set ra.status = :status, ra.batch_id = :batchId, last_updated = :lastUpdated, ra.version = version + 1 where ra.id in (:idList)"

            def query = sessionFactory.currentSession.createSQLQuery(sql)
            query.setTimestamp("lastUpdated", new Date())
            query.setParameterList("idList", idList)
            query.setString("status", ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_POSTPONE.toString())
            query.setLong("batchId", receivableAnticipationBatchAnalysis.id)
            query.executeUpdate()
        }
    }

    public void processBatchApproval() {
        Map search = [:]
        search.columnList = ["id", "receivableAnticipation.id", "batch"]
        search.status = ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_APPROVAL
        List<Map> receivableAnticipationAnalysisList = ReceivableAnticipationAnalysis.query(search).list()

        if (!receivableAnticipationAnalysisList) return

        for (Map receivableAnticipationAnalysis : receivableAnticipationAnalysisList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                ReceivableAnticipation anticipation = receivableAnticipationService.approve(receivableAnticipationAnalysis."receivableAnticipation.id", receivableAnticipationAnalysis.batch.observation)
                receivableAnticipationNonAutomaticApprovalRuleBypassService.processBypass(anticipation)
            }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchApproval >> Falha ao aprovar a antecipação [${receivableAnticipationAnalysis."receivableAnticipation.id"}]", onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableAnticipationAnalysisService.setAsError(receivableAnticipationAnalysis.id)
                }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchApproval >> Falha ao alterar o status da análise [${receivableAnticipationAnalysis.id}]"])
            }
        }
    }

    public void processBatchDenial() {
        Map search = [:]
        search.columnList = ["id", "receivableAnticipation.id", "batch"]
        search.status = ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_DENIAL
        List<Map> receivableAnticipationAnalysisList = ReceivableAnticipationAnalysis.query(search).list()

        if (!receivableAnticipationAnalysisList) return

        for (Map receivableAnticipationAnalysis : receivableAnticipationAnalysisList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                receivableAnticipationService.deny(receivableAnticipationAnalysis."receivableAnticipation.id", receivableAnticipationAnalysis.batch.observation, receivableAnticipationAnalysis.batch.denialReason)
            }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchDenial >> Falha ao negar a antecipação [${receivableAnticipationAnalysis."receivableAnticipation.id"}]", onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableAnticipationAnalysisService.setAsError(receivableAnticipationAnalysis.id)
                }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchDenial >> Falha ao alterar o status da análise [${receivableAnticipationAnalysis.id}]"])
            }
        }
    }

    public void processBatchPostpone() {
        Map search = [:]
        search.columnList = ["id", "batch"]
        search.status = ReceivableAnticipationAnalysisStatus.AWAITING_BATCH_POSTPONE
        List<Map> receivableAnticipationAnalysisList = ReceivableAnticipationAnalysis.query(search).list()

        if (!receivableAnticipationAnalysisList) return

        for (Map receivableAnticipationAnalysis : receivableAnticipationAnalysisList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                receivableAnticipationAnalysisService.postponeAnalysis(receivableAnticipationAnalysis.id, receivableAnticipationAnalysis.batch.observation)
            }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchPostpone >> Falha ao postergar a antecipação [${receivableAnticipationAnalysis.id}]", onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableAnticipationAnalysisService.setAsError(receivableAnticipationAnalysis.id)
                }, [logErrorMessage: "ReceivableAnticipationAnalysisBatchService.processBatchPostpone >> Falha ao alterar o status da análise [${receivableAnticipationAnalysis.id}]"])
            }
        }
    }

    private List<Long> listReceivableAnticipationAnalysisAwaitingAnalysis(Long customerId, BillingType billingType) {
        Map search = [:]
        search.column = "id"
        search.customerId = customerId
        search.receivableAnticipationBillingType = billingType
        search."status[in]" = ReceivableAnticipationAnalysisStatus.listAwaitingAnalysisStatus()

        return ReceivableAnticipationAnalysis.query(search).list()
    }

    private ReceivableAnticipationBatchAnalysis saveBatch(String observation, ReceivableAnticipationDenialReason denialReason) {
        ReceivableAnticipationBatchAnalysis receivableAnticipationAnalysisBatch = new ReceivableAnticipationBatchAnalysis()

        receivableAnticipationAnalysisBatch.observation = observation
        receivableAnticipationAnalysisBatch.denialReason = denialReason
        receivableAnticipationAnalysisBatch.user = UserUtils.getCurrentUser()

        return receivableAnticipationAnalysisBatch.save(failOnError: true)
    }
}
