package com.asaas.service.api

import com.asaas.api.ApiFinancialInstitutionParser
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class ApiFinancialInstitutionService extends ApiBaseService {

    def apiResponseBuilderService
    def financialInstitutionService

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)

        Map filters = ApiFinancialInstitutionParser.parseListFilters(params)

        Map result = financialInstitutionService.listOrderedByBankPriority(customer, getOffset(params), getLimit(params), filters)
        List<Map> financialInstitutionMapList = result.items.collect { ApiFinancialInstitutionParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(financialInstitutionMapList, getLimit(params), getOffset(params), result.totalCount)
    }
}
