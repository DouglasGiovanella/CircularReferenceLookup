package com.asaas.service.customerdocumentanalysisstatus

import com.asaas.domain.user.User
import grails.transaction.Transactional

@Transactional
class CustomerDocumentAnalysisStatusProxyService {

    def customerDocumentAnalysisStatusService

    public Integer countDocumentAnalysisInQueue(List<Long> customerIdList) {
        return customerDocumentAnalysisStatusService.countDocumentAnalysisInQueue(customerIdList)
    }

    public Long findNextDocumentAnalysisInQueue(User analyst, List<Long> customerIdList) {
        return customerDocumentAnalysisStatusService.findNextDocumentAnalysisInQueue(analyst, customerIdList)
    }
}
