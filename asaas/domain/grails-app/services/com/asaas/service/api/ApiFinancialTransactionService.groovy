package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiFinancialTransactionParser
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.financialtransaction.FinancialTransactionListVo
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class ApiFinancialTransactionService extends ApiBaseService {

    def apiResponseBuilderService
    def financialTransactionExportService
    def financialTransactionService
    def financialTransactionListService

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)

        Map search = [max: getLimit(params), offset: getOffset(params), sort: 'id', order: params.order ?: 'desc']

        if (params.startDate || params.period) {
            Date startDate = ApiBaseParser.parseAndValidateDate(params.startDate, "startDate") ?: financialTransactionService.calculateStartDateByPeriod(params.period)
            Date finishDate = ApiBaseParser.parseAndValidateDate(params.finishDate, "finishDate") ?: financialTransactionService.calculateFinishDateByPeriod(params.period)

            search << [initialDate: startDate, finalDate: finishDate]
        }

        if (params.startId) search.startId = params.long("startId")
        if (params.finishId) search.finishId = params.long("finishId")

        Boolean dateWithTimeEnaled = CustomerParameter.getValue(customer, CustomerParameterName.API_RESPONSE_DATE_WITH_TIME)

        FinancialTransactionListVo listVo = financialTransactionListService.list(customer, search)
        List<Map> responseMap = listVo.transactionList.collect { ApiFinancialTransactionParser.buildResponseItem(it, dateWithTimeEnaled) }

        return apiResponseBuilderService.buildList(responseMap, getLimit(params), getOffset(params), listVo.transactionList.totalCount)
    }

    public Map export(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiFinancialTransactionParser.parseExportParams(params)

        Map transactionCountQuery = [:]
        transactionCountQuery.customerId = customer.id
        transactionCountQuery."transactionDate[ge]" = fields.startDate
        transactionCountQuery."transactionDate[le]" = fields.endDate

        Map exportResponse = financialTransactionExportService.export(fields.fileType, customer, UserUtils.getCurrentUser(), fields.startDate, fields.endDate, params.totalFinancialTransactionFiltered, 0)

        return apiResponseBuilderService.buildSuccess([recipientEmail: exportResponse.recipientEmail])
    }
}
