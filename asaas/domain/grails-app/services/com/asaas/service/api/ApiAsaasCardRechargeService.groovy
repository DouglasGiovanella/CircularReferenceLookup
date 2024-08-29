package com.asaas.service.api

import com.asaas.api.ApiAsaasCardRechargeParser
import com.asaas.api.ApiBaseParser
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.customer.Customer
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiAsaasCardRechargeService extends ApiBaseService {

	def apiResponseBuilderService
    def asaasCardService
	def asaasCardRechargeService
	def messageService

	def save(params) {
		if (!params.id) {
			return apiResponseBuilderService.buildError("required", "asaasCard", "id", ["Id"])
		}

        Customer customer = getProviderInstance(params)
		AsaasCardRecharge asaasCardRecharge = asaasCardRechargeService.save(AsaasCard.find(params.id, customer.id), Utils.bigDecimalFromString(params.value))

		if (asaasCardRecharge.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(asaasCardRecharge)
		}

		return apiResponseBuilderService.buildSuccess(ApiAsaasCardRechargeParser.buildResponseItem(asaasCardRecharge, [buildCriticalAction: true]))
	}

	def list(params) {
        List<AsaasCardRecharge> rechargesList = []

        if (params.id) {
            AsaasCard asaasCard = AsaasCard.find(params.id, getProvider(params))

            List<Long> asaasCardIdList = asaasCardService.listReissuedCardIds(asaasCard.id)
            asaasCardIdList.add(asaasCard.id)

            rechargesList = AsaasCardRecharge.query(['asaasCardId[in]': asaasCardIdList]).list(max: getLimit(params), offset: getOffset(params))
        } else {
            rechargesList = AsaasCardRecharge.query([customerId: getProvider(params)]).list(max: getLimit(params), offset: getOffset(params))
        }

		List<Map> responseItems = rechargesList.collect { asaasCardRecharge -> ApiAsaasCardRechargeParser.buildResponseItem(asaasCardRecharge) }

		return apiResponseBuilderService.buildList(responseItems, getLimit(params), getOffset(params), rechargesList.totalCount)
	}
}
