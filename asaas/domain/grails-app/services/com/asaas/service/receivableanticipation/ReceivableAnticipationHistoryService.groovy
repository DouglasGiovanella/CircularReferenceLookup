package com.asaas.service.receivableanticipation

import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationHistory
import com.asaas.domain.receivableanticipationanalysis.ReceivableAnticipationAnalysis
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.receivableanticipationanalysis.ReceivableAnticipationAnalysisStatus
import com.asaas.user.UserUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationHistoryService {

    public ReceivableAnticipationHistory save(ReceivableAnticipation anticipation) {
        return save(anticipation, null)
    }

    public ReceivableAnticipationHistory save(ReceivableAnticipation anticipation, String observation) {
        BusinessValidation validatedBusiness = validate(anticipation)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        ReceivableAnticipationHistory receivableAnticipationHistory = new ReceivableAnticipationHistory()
        receivableAnticipationHistory.receivableAnticipation = anticipation
        receivableAnticipationHistory.user = findUser(anticipation)
        receivableAnticipationHistory.status = anticipation.status
        receivableAnticipationHistory.observation = observation

        return receivableAnticipationHistory.save(failOnError: true)
    }

    private User findUser(ReceivableAnticipation anticipation) {
        User user = UserUtils.getCurrentUser()
        if (user) return user

        if (anticipation.status.isAwaitingAnalysisBatch()) {
            Map search = [:]
            search.receivableAnticipationId = anticipation.id
            search."status[in]" = ReceivableAnticipationAnalysisStatus.listAwaitingBatchStatus()

            ReceivableAnticipationAnalysis analysis = ReceivableAnticipationAnalysis.query(search).get()

            if (analysis && analysis.batch) return analysis.batch.user
        }

        return null
    }

    private BusinessValidation validate(ReceivableAnticipation anticipation) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!anticipation) {
            validatedBusiness.addError("receivableAnticipationHistory.anticipation.required")
            return validatedBusiness
        }

        return validatedBusiness
    }
}
