package com.asaas.service.facematchcriticalactionanalysisrequest

import com.asaas.analysisrequest.AnalysisRequestStatus
import com.asaas.domain.facematchcriticalaction.FacematchCriticalAction
import com.asaas.domain.facematchcriticalaction.FacematchCriticalActionAnalysisRequest
import grails.transaction.Transactional

@Transactional
class CreateFacematchCriticalActionAnalysisRequestService {

    public FacematchCriticalActionAnalysisRequest save(FacematchCriticalAction facematchCriticalAction) {
        FacematchCriticalActionAnalysisRequest facematchCriticalActionAnalysisRequest = new FacematchCriticalActionAnalysisRequest()
        facematchCriticalActionAnalysisRequest.customer = facematchCriticalAction.requester.customer
        facematchCriticalActionAnalysisRequest.facematchCriticalAction = facematchCriticalAction
        facematchCriticalActionAnalysisRequest.status = AnalysisRequestStatus.MANUAL_ANALYSIS_REQUIRED

        facematchCriticalActionAnalysisRequest.save(failOnError: true)
        return facematchCriticalActionAnalysisRequest
    }
}
