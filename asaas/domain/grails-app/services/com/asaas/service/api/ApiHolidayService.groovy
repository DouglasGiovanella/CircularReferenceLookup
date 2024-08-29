package com.asaas.service.api

import com.asaas.api.ApiHolidayParser
import com.asaas.domain.holiday.Holiday

import grails.transaction.Transactional

@Transactional
class ApiHolidayService extends ApiBaseService {

    def apiResponseBuilderService

    public Map list(Map params) {
        Integer max = getLimit(params)
        Integer offset = getOffset(params)

        Map filters = ApiHolidayParser.parseListFilters(params)

        List<Holiday> holidays = Holiday.query(filters).list(max: max, offset: offset)

        List<Map> responseItem = holidays.collect { ApiHolidayParser.buildResponseItem(it) }

        return apiResponseBuilderService.buildList(responseItem, max, offset, holidays.totalCount)
    }
}
