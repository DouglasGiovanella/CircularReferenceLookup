package com.asaas.service.api

import com.asaas.api.ApiCustomerCommissionItemParser
import com.asaas.api.ApiCustomerCommissionParser
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionItem

import grails.transaction.Transactional

@Transactional
class ApiCustomerCommissionService extends ApiBaseService {

    def apiResponseBuilderService

    public Map find(Map params) {
        CustomerCommission customerCommission = CustomerCommission.find(params.id, getProviderInstance(params))

        return apiResponseBuilderService.buildSuccess(ApiCustomerCommissionParser.buildResponseItem(customerCommission))
    }

    public Map list(Map params) {
        Map parsedFilters = ApiCustomerCommissionParser.parseFilters(params)

        Map queryParams = parsedFilters
        queryParams.customer = getProviderInstance(params)

        List<CustomerCommission> customerCommissionList = CustomerCommission.query(queryParams).list(max: getLimit(params), offset: getOffset(params))
        List<Map> customerCommissions = customerCommissionList.collect( { ApiCustomerCommissionParser.buildResponseItem(it) } )

        return apiResponseBuilderService.buildList(customerCommissions, getLimit(params), getOffset(params), customerCommissionList.totalCount)
    }

    public Map findItem(Map params) {
        CustomerCommissionItem customerCommissionItem = CustomerCommissionItem.find(params.id, getProviderInstance(params))

        return apiResponseBuilderService.buildSuccess(ApiCustomerCommissionItemParser.buildResponseItem(customerCommissionItem))
    }

    public Map listItems(Map params) {
        Map parsedFilters = ApiCustomerCommissionItemParser.parseFilters(params)
        parsedFilters.ownerAccount = getProviderInstance(params)

        List<CustomerCommissionItem> customerCommissionItemList = CustomerCommissionItem.query(parsedFilters).list(max: getLimit(params), offset: getOffset(params))
        List<Map> customerCommissionItems = customerCommissionItemList.collect( { ApiCustomerCommissionItemParser.buildResponseItem(it) } )

        return apiResponseBuilderService.buildList(customerCommissionItems, getLimit(params), getOffset(params), customerCommissionItemList.totalCount)
    }
}
