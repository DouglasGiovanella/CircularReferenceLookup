package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCityParser
import com.asaas.domain.city.City

import grails.transaction.Transactional

@Transactional
class ApiCityService extends ApiBaseService {

	def apiResponseBuilderService
	def cityService

	def find(params) {
        City city = cityService.find(params)
        return apiResponseBuilderService.buildSuccess(ApiCityParser.buildResponseItem(city))
	}

	def list(params) {
		List<City> cityList = cityService.list(getLimit(params), getOffset(params), params)
		List<Map> cities = []

		for(city in cityList) {
			if (ApiBaseParser.getApiVersion() < 3) {
				cities << [city: ApiCityParser.buildResponseItem(city)]
			} else {
				cities << ApiCityParser.buildResponseItem(city)
			}
		}

		return apiResponseBuilderService.buildList(cities, getLimit(params), getOffset(params), cityList.totalCount)
	}
}
