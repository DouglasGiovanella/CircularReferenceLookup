package com.asaas.service.receivableanticipationanalysis

import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysis
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysisHistory
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalyst
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationAnalysisHistoryService {

    public void save(ReceivableAnticipationAnalysis analysis) {
        ReceivableAnticipationAnalysisHistory history = new ReceivableAnticipationAnalysisHistory()
        history.receivableAnticipationAnalysis = analysis
        history.status = analysis.status
        history.nextAnalysis = analysis.nextAnalysis

        history.analyst = getAnalystForHistory(analysis)

        history.save(failOnError: true)
    }

    private ReceivableAnticipationAnalyst getAnalystForHistory(ReceivableAnticipationAnalysis analysis) {
        User user = UserUtils.getCurrentUser()
        if (!user) user = analysis.batch?.user

        if (!user) return analysis.analyst
        if (analysis.analyst?.user?.id == user.id) return analysis.analyst

        return ReceivableAnticipationAnalyst.query([userId: user.id]).get()
    }
}
