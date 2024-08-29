package com.asaas.service.abtest

import com.asaas.domain.abtest.AbTestVariant
import com.asaas.domain.abtest.AbTestVariantHistory
import com.asaas.domain.user.User
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class AbTestVariantHistoryService {

    public AbTestVariantHistory create(AbTestVariant abTestVariant) {
        User user = UserUtils.getCurrentUser()

        AbTestVariantHistory abTestVariantHistory = new AbTestVariantHistory()
        abTestVariantHistory.abTestVariant = abTestVariant
        abTestVariantHistory.weight = abTestVariant.weight
        abTestVariantHistory.user = user
        abTestVariantHistory.isWinner = abTestVariant.isWinner

        abTestVariantHistory.save(failOnError: true)

        return abTestVariantHistory
    }
}
