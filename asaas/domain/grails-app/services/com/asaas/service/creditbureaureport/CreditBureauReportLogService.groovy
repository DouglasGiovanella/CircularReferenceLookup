package com.asaas.service.creditbureaureport

import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportLog
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CreditBureauReportLogService {

    public void save(Customer customer, CreditBureauReport creditBureauReport, String responseContent) {
        Long creditBureauReportId = creditBureauReport?.id
        Long customerId = customer.id

        Utils.withNewTransactionAndRollbackOnError({
            CreditBureauReportLog creditBureauReportLog = new CreditBureauReportLog()
            creditBureauReportLog.creditBureauReportId = creditBureauReportId
            creditBureauReportLog.customerId = customerId
            creditBureauReportLog.requestContent = responseContent
            creditBureauReportLog.save(failOnError: true)
        }, [logErrorMessage: "CreditBureauReportLog > Erro ao inserir Log contendo o response da Consulta Serasa."])
    }
}
