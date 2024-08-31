package com.asaas.service.api

import com.asaas.api.ApiTimelineEventParser
import com.asaas.exception.BusinessException

import grails.transaction.Transactional

@Transactional
class ApiTimelineEventService extends ApiBaseService {

    def apiResponseBuilderService
    def paymentNotificationHistoryService

    def list(params) {
        Integer max = getLimit(params)
        Integer offset = getOffset(params)
        Map fields = ApiTimelineEventParser.parseRequestParams(getProvider(params), params)

        if (!fields.paymentId && !fields.customerAccountId) throw new BusinessException("Obrigatório informar um dos parâmetros 'paymentId' ou 'customerAccountId'")

        Map notificationHistoryListData = paymentNotificationHistoryService.list(fields, offset, fields.notificationDispatcherOffset, max)

        List<Map> responseItems = notificationHistoryListData.sequencedResult.list.collect { ApiTimelineEventParser.buildResponseItem(it) }
        List<Map> extraData = [[notificationDispatcherOffset: notificationHistoryListData.notificationDispatcherOffset]]

        return apiResponseBuilderService.buildList(responseItems, max, offset, notificationHistoryListData.sequencedResult.list.size(), extraData)
    }
}
