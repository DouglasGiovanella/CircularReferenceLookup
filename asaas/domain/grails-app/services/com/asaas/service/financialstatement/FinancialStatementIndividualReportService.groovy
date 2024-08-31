package com.asaas.service.financialstatement

import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.exception.BusinessException
import com.asaas.financialstatement.BalanceType
import com.asaas.financialstatement.FinancialStatementBalanceTypeUtils
import com.asaas.financialstatement.FinancialStatementCategoryUtils
import com.asaas.financialstatement.FinancialStatementReportSearchBuilder
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementIndividualReportService {

    def grailsApplication

    public Map list(Map params, Integer limit, Integer offset) {
        if (!params.containsKey("statementDate")) validatePeriod(params)

        Map search = FinancialStatementReportSearchBuilder.build(params)
        List<FinancialStatement> financialStatementList = FinancialStatement.query(search).list(max: limit, offset: offset)

        List<Map> buildedStatementList = buildFinancialStatementList(financialStatementList)

        return [financialStatementList: buildedStatementList, totalCount: financialStatementList.totalCount, totalValue: buildedStatementList.sum { it.value }]
    }

    public List<Map> listBankAccountDataList() {
        List<Map> bankAccountDataList = grailsApplication.config.bank.api.asaas.collect { it.value }
        bankAccountDataList += grailsApplication.config.bank.api.customer.collect { it.value }

        return bankAccountDataList.unique()
    }

    private List<Map> buildFinancialStatementList(List<FinancialStatement> financialStatementList) {
        List<Map> result = []

        financialStatementList.each {
            BigDecimal financialStatementValue = it.value
            String movimentationType = 'Entrada'
            if (it.financialStatementType.isExpense()) {
                financialStatementValue *= -1
                movimentationType = 'Saída'
            }

            BalanceType balanceType = FinancialStatementBalanceTypeUtils.getBalanceType(it.financialStatementType)
            result.add([
                statementDate: it.statementDate,
                financialStatementId: it.id,
                financialCategoryLabel: it.financialStatementType.getLabel(),
                movimentationType: movimentationType,
                balanceTypeLabel: Utils.getMessageProperty("BalanceType.${balanceType}"),
                bankAccountLabel: getBankAccountLabel(it.bankAccountId),
                value: financialStatementValue ?: 0
            ])
        }

        return result
    }

    private String getBankAccountLabel(String bankAccountId) {
        if (!bankAccountId) return null

        List<Map> bankAccountDataList = listBankAccountDataList()
        return bankAccountDataList.find { it.id == bankAccountId }?.label
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
