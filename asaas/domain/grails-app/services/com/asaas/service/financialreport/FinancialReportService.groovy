package com.asaas.service.financialreport

import com.asaas.financialreport.FinancialReportType
import com.asaas.integration.accountingapplication.AccountingApplicationManager
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional
import sun.reflect.generics.reflectiveObjects.NotImplementedException

@Transactional
class FinancialReportService {

    def messageService

    public void sendToAccountingApplication(FinancialReportType financialReportType, Map search, String requestUserEmail) {
        try {
            String url
            switch (financialReportType) {
                case FinancialReportType.FINANCIAL_STATEMENT_CONSOLIDATION:
                    url = "/financialStatementConsolidationReport/export"
                    break
                case FinancialReportType.FINANCIAL_STATEMENT_INDIVIDUAL:
                    url = "/financialStatementIndividualReport/export"
                    break
                default:
                    throw new NotImplementedException()
            }

            AccountingApplicationManager accountingApplicationManager = new AccountingApplicationManager()
            accountingApplicationManager.post(url, [requestUserEmail: requestUserEmail, search: search])

            if (!accountingApplicationManager.isSuccessful()) {
                throw new RuntimeException("O Accounting retornou um status diferente de sucesso ao enviar os dados para AccountingApplication. StatusCode: [${accountingApplicationManager.statusCode}], ResponseBody: [${accountingApplicationManager.responseBody}]")
            }
        } catch (Exception exception) {
            AsaasLogger.error("FinancialReportService.sendToAccountingApplication >>> Erro ao enviar os dados para AccountingApplication. Tipo: [${financialReportType}]. Email: [${requestUserEmail}]. Search: [${search}]", exception)
            throw new RuntimeException("Erro ao enviar relatório para API Accounting")
        }
    }
}
