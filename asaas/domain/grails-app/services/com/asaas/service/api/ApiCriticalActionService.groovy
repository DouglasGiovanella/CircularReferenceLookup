package com.asaas.service.api

import com.asaas.api.ApiCriticalActionParser
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class ApiCriticalActionService extends ApiBaseService  {

	def apiResponseBuilderService

	def criticalActionService

    def criticalActionNotificationService

    def list(params) {
        Customer customer = getProviderInstance(params)
        Map filters = ApiCriticalActionParser.parseFilters(params)
        filters.order = "desc"

        List<CriticalAction> actions = CriticalAction.notAuthorized(filters + [customer: customer]).list(max: getLimit(params), offset: getOffset(params))

    	List<Map> reponseItems = []

    	actions.each { action ->
    		reponseItems << ApiCriticalActionParser.buildResponseItem(action)
    	}

        List<Map> extraData = [ApiCriticalActionParser.buildExtraDataItem(customer)]

        return apiResponseBuilderService.buildList(reponseItems, getLimit(params), getOffset(params), actions.totalCount, extraData)
    }

    def group(params) {
    	criticalActionService.group(getProviderInstance(params), params.actions)

    	return list(params)
    }

    def groupAndAuthorize(params) {
        CriticalActionGroup group = criticalActionService.groupAndAuthorize(getProviderInstance(params), params.actions, params.authorizationToken)

        return ApiCriticalActionParser.buildGroupResponseItem(group)
    }

    def cancel(params) {
    	criticalActionService.cancelList(getProviderInstance(params), params.actions)

    	return list(params)
    }

    def authorizeGroup(params) {
    	CriticalActionGroup group = criticalActionService.authorizeGroup(getProviderInstance(params), Long.valueOf(params.id), params.authorizationToken)

    	return ApiCriticalActionParser.buildGroupResponseItem(group)
    }

    def cancelGroup(params) {
        criticalActionService.cancelGroup(getProviderInstance(params), params.long("id"))

        return list(params)
    }

    def cancelExpired(params) {
        criticalActionService.cancelExpired(getProviderInstance(params))

        return list(params)
    }

    def resendAuthorizationTokenSMS(params) {
        criticalActionNotificationService.resendAuthorizationTokenSMS(getProviderInstance(params), params.long("id"))

        return apiResponseBuilderService.buildSuccess([success: true])
    }
}
