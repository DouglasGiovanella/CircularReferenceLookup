package com.asaas.service.api

import com.asaas.api.ApiReferralParser
import com.asaas.domain.Referral
import com.asaas.domain.customer.Customer

import grails.transaction.Transactional

@Transactional
class ApiReferralService extends ApiBaseService {

    def apiResponseBuilderService
    def referralService
    def referralBatchService

    public inviteAgain(Map params) {
        Referral referral = Referral.find(Long.valueOf(params.id), getProvider(params))

        if (!referral) return apiResponseBuilderService.buildNotFoundItem()

        referralService.inviteAgain(referral)

        return apiResponseBuilderService.buildSuccess(ApiReferralParser.buildResponseItem(referral))
    }

    public inviteFriends(Map params) {
        if (params.containsKey("invitedFriends")) {
            referralBatchService.inviteFriendList(getProviderInstance(params), params.invitedFriends)
        } else {
            referralBatchService.saveInvitations(getProviderInstance(params), params.invitationsListMap)
        }

        return apiResponseBuilderService.buildSuccess([])
    }

    public listAlreadyInvited(Map params){
        Customer customer = getProviderInstance(params)

        List<Referral> referrals = Referral.query([searchedText: params.searchedText, invitedByCustomer: customer, order: "desc"]).list(max: getLimit(params), offset: getOffset(params))

        List<Map> referralList = []

        for (Referral referral : referrals) {
            referralList += ApiReferralParser.buildResponseItem(referral)
        }

        return apiResponseBuilderService.buildList(referralList, getLimit(params), getOffset(params), referrals.totalCount)
    }
}
