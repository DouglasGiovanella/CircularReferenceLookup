package com.asaas.service.feenegotiation

import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class FeeNegotiationService {

    public Boolean isAllowedChangeFeeBelowDefaultLimit(Long customerId) {
        Boolean customerHasDeal = CustomerDealInfo.query([exists: true, customerId: customerId]).get().asBoolean()
        if (customerHasDeal) return true

        if (UserUtils.getCurrentUser()?.belongsToTechnicalSupportTeam()) return true

        return false
    }
}
