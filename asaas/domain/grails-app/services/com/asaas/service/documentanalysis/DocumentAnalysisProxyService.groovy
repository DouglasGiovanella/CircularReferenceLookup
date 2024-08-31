package com.asaas.service.documentanalysis

import com.asaas.documentanalysis.DocumentAnalysisPostponedReason
import com.asaas.documentanalysis.DocumentAnalysisRejectReason
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.documentanalysis.adapter.DocumentAnalysisAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.user.User
import grails.transaction.Transactional

@Transactional
class DocumentAnalysisProxyService {

    def documentAnalysisService

    public void postponeAnalysis(Customer customer, DocumentAnalysisPostponedReason reason, String reasonDescription, User currentUser) {
        documentAnalysisService.postponeAnalysis(customer, reason, reasonDescription, currentUser)
    }

    public DocumentAnalysisAdapter createAnalysis(Customer customer, User analyst) {
        DocumentAnalysis documentAnalysis = documentAnalysisService.createAnalysis(customer, analyst)
        if (!documentAnalysis) return null
        return new DocumentAnalysisAdapter(documentAnalysis)
    }

    public DocumentAnalysisAdapter findLastDocumentAnalysis(Customer customer) {
        DocumentAnalysis documentAnalysis = documentAnalysisService.findLastDocumentAnalysis(customer)
        if (!documentAnalysis) return null
        return new DocumentAnalysisAdapter(documentAnalysis)
    }

    public Long findInProgressAnalysisId(Customer customer, User analyst) {
        return documentAnalysisService.findInProgressAnalysisId(customer, analyst)
    }

    public DocumentAnalysisAdapter saveManually(Long documentAnalysisId, Customer customer, DocumentAnalysisStatus status, String observations, DocumentAnalysisRejectReason rejectReason, List<Map> analysisResultList) {
        DocumentAnalysis documentAnalysis = documentAnalysisService.saveManually(documentAnalysisId, customer, status, observations, rejectReason, analysisResultList)
        if (!documentAnalysis) return null
        return new DocumentAnalysisAdapter(documentAnalysis)
    }
}
