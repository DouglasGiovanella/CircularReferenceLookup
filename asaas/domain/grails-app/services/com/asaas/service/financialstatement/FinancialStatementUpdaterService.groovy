package com.asaas.service.financialstatement

import com.asaas.domain.financialstatement.FinancialStatement
import grails.transaction.Transactional

@Transactional
class FinancialStatementUpdaterService {

    def financialStatementService

    public void setToError(Long financialStatementId) {
        FinancialStatement financialStatement = FinancialStatement.get(financialStatementId)
        financialStatementService.setAsError(financialStatement)
    }

    public void setToAwaitingCalculateFinancialStatementValue(Long financialStatementId) {
        FinancialStatement financialStatement = FinancialStatement.get(financialStatementId)
        financialStatementService.setAsAwaitingCalculateFinancialStatementValue(financialStatement)
    }
}
