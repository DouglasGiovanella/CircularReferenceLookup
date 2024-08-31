package com.asaas.service.promotionalcodeGroup

import com.asaas.domain.promotionalcode.PromotionalCode
import com.asaas.promotionalcodegroup.cache.PromotionalCodeGroupCache
import com.asaas.promotionalcodegroup.cache.PromotionalCodeGroupCacheVO

import grails.transaction.Transactional

@Transactional
class PromotionalCodeGroupService {

    def grailsApplication

    public PromotionalCodeGroupCacheVO getReferralPromotionPromotionalCodeGroup(Long invitedByCustomerId, Boolean isInvitedCustomerNaturalPerson) {
        if (isInvitedCustomerNaturalPerson) {
            String firstNaturalPersonGroupName = grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstNaturalPerson.name
            Boolean hasFirstIndication = hasPromotionalCodeWithPromotionalCodeGroup(invitedByCustomerId, firstNaturalPersonGroupName)
            if (!hasFirstIndication) return getPromotionalCodeGroup(firstNaturalPersonGroupName)

            String secondNaturalPersonGroupName = grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondNaturalPerson.name
            Boolean hasSecondIndication = hasPromotionalCodeWithPromotionalCodeGroup(invitedByCustomerId, secondNaturalPersonGroupName)
            if (!hasSecondIndication) return getPromotionalCodeGroup(secondNaturalPersonGroupName)

            return getPromotionalCodeGroup(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoNaturalPerson.name)
        }

        String firstLegalPersonGroupName = grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedFirstLegalPerson.name
        Boolean hasFirstIndication = hasPromotionalCodeWithPromotionalCodeGroup(invitedByCustomerId, firstLegalPersonGroupName)
        if (!hasFirstIndication) return getPromotionalCodeGroup(firstLegalPersonGroupName)

        String secondLegalPersonGroupName = grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedSecondLegalPerson.name
        Boolean hasSecondIndication = hasPromotionalCodeWithPromotionalCodeGroup(invitedByCustomerId, secondLegalPersonGroupName)
        if (!hasSecondIndication) return getPromotionalCodeGroup(secondLegalPersonGroupName)

        return getPromotionalCodeGroup(grailsApplication.config.asaas.referral.promotionalCodeGroup.invitedValidatedOverTwoLegalPerson.name)
    }

    public BigDecimal getPromotionalCodeGroupValue(String promotionalCodeGroupName) {
        return PromotionalCodeGroupCache.instance.getDiscountByName(promotionalCodeGroupName)
    }

    private PromotionalCodeGroupCacheVO getPromotionalCodeGroup(String promotionalCodeGroupName) {
        return PromotionalCodeGroupCache.instance.findByName(promotionalCodeGroupName)
    }

    private Boolean hasPromotionalCodeWithPromotionalCodeGroup(Long invitedByCustomerId, String promotionalCodeGroupName) {
        return PromotionalCode.query([exists: true, customerId: invitedByCustomerId, promotionalCodeGroupName: promotionalCodeGroupName]).get().asBoolean()
    }
}
