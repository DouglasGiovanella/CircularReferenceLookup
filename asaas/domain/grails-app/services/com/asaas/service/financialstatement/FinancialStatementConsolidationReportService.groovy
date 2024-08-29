package com.asaas.service.financialstatement

import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.exception.BusinessException
import com.asaas.financialstatement.FinancialStatementBalanceTypeUtils
import com.asaas.financialstatement.FinancialStatementCategoryUtils
import com.asaas.financialstatement.FinancialStatementReportSearchBuilder
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class FinancialStatementConsolidationReportService {

    public Map buildGroupFinancialStatementsInfo(Map params) {
        validatePeriod(params)

        Map search = FinancialStatementReportSearchBuilder.build(params)
        List<FinancialStatement> financialStatementList = FinancialStatement.query(search).list()
        List<Map> groupedFinancialStatementInfoList = groupFinancialStatementList(financialStatementList)

        return [financialStatementList: groupedFinancialStatementInfoList, totalCount: groupedFinancialStatementInfoList.size(), totalValue: groupedFinancialStatementInfoList.sum { it.value }]
    }

    private List<Map> groupFinancialStatementList(List<FinancialStatement> financialStatementList) {
        List<Map> groupedList = []

        Map listGroupedByStatementDate = financialStatementList.groupBy { it.statementDate }
        listGroupedByStatementDate.each { Date statementDate, List<FinancialStatement> financialStatement ->
            Map listGroupedByStatementDateAndFinancialStatementType = financialStatement.groupBy { it.financialStatementType }

            listGroupedByStatementDateAndFinancialStatementType.each { FinancialStatementType financialStatementType, List<FinancialStatement> financialStatementGrouped ->
                BigDecimal financialStatementSummedValue = financialStatementGrouped.value.sum()
                String movimentationTypeLabel = 'Entrada'
                Boolean movimentationType = true
                if (financialStatementType.isExpense()) {
                    financialStatementSummedValue *= -1
                    movimentationTypeLabel = 'Saída'
                    movimentationType = false
                }

                groupedList.add([
                        statementDate: statementDate,
                        financialStatementType: financialStatementType,
                        financialCategoryLabel: financialStatementType.getLabel(),
                        movimentationType: movimentationType,
                        movimentationTypeLabel: movimentationTypeLabel,
                        balanceType: FinancialStatementBalanceTypeUtils.getBalanceType(financialStatementType),
                        value: financialStatementSummedValue ?: 0
                ])
            }
        }

        return groupedList
    }

    private void validatePeriod(Map params) {
        final Integer daysLimitToFilter = 30

        Date startDate = CustomDateUtils.fromString(params."statementDate[ge]")
        Date endDate = CustomDateUtils.fromString(params."statementDate[le]")

        if (!startDate || !endDate) {
            throw new BusinessException("É obrigatório informar o período inicial e final, com limite de ${daysLimitToFilter} dias")
        }

        if (CustomDateUtils.calculateDifferenceInDays(startDate, endDate) > daysLimitToFilter) {
            throw new BusinessException("O período máximo para filtro é de ${daysLimitToFilter} dias")
        }
    }
}
