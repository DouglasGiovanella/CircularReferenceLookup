package com.asaas.service.api

import com.asaas.domain.businessactivity.BusinessActivity
import com.asaas.api.ApiBusinessActivityParser

import grails.transaction.Transactional

@Transactional
class ApiBusinessActivityService extends ApiBaseService {

	def apiResponseBuilderService

	def list(params) {
		List<BusinessActivity> businessActivityList = BusinessActivity.listOrdered()

		List<Map> responseItems = businessActivityList.collect { ApiBusinessActivityParser.buildResponseItem(it, null) }

		return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), businessActivityList.size())
	}

}
