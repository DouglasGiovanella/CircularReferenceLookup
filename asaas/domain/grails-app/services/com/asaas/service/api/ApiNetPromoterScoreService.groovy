package com.asaas.service.api

import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.Customer
import com.asaas.domain.netpromoterscore.NetPromoterScore
import com.asaas.netpromoterscore.NetPromoterScoreOrigin
import com.asaas.utils.StringUtils

import grails.transaction.Transactional

@Transactional
class ApiNetPromoterScoreService extends ApiBaseService {

	def netPromoterScoreService
	def apiResponseBuilderService
	def referralService
	def grailsApplication

	def update(params) {
        Customer customer = getProviderInstance(params)

        NetPromoterScoreOrigin origin = NetPromoterScoreOrigin.convert(ApiMobileUtils.getMobileAppPlatform())

        NetPromoterScore netPromoterScore = netPromoterScoreService.update(customer, Long.valueOf(params.id), Integer.valueOf(params.score), StringUtils.replaceEmojis(params.observations, ""), origin)

        Map responseMap = [:]
        responseMap.canShowReferral = netPromoterScoreService.canShowReferral(netPromoterScore)

		if (responseMap.canShowReferral) {
			responseMap.referralIndicationUrl = referralService.buildReferralUrl(customer)
			responseMap.referralDiscountValue = grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited

            responseMap.possibleCustomerAccountInvitationsCount = referralService.getCustomerAccountInvitationLimit(customer)
		}

		return apiResponseBuilderService.buildSuccess(responseMap)
	}

	def ignore(params) {
        NetPromoterScoreOrigin origin = NetPromoterScoreOrigin.convert(ApiMobileUtils.getMobileAppPlatform())

        netPromoterScoreService.ignore(getProviderInstance(params), Long.valueOf(params.id), origin)

		return apiResponseBuilderService.buildSuccess([success: true])
	}

    public Map buildNetPromoterScoreResponseItem(Customer customer) {
        NetPromoterScore netPromoterScore = NetPromoterScore.readyToBeAnswered([customer: customer]).get()

        if (!netPromoterScore) return null

        Map responseItem = [:]
        responseItem.id = netPromoterScore.id
        responseItem.isFirst = netPromoterScore.isFirst

        if (netPromoterScore.isFirst) responseItem += netPromoterScoreService.buildHoursSavedInfo(customer)

        return responseItem
    }
}
