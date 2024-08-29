package com.asaas.service.bradesco

import com.asaas.domain.customer.Customer
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import grails.transaction.Transactional

@Transactional
class BradescoOnboardingService {

    public Boolean notFinishedOnboarding(Customer customer) {
        if (!CustomerPartnerApplication.hasBradesco(customer.id)) return false

        return !hasFinishedOnboarding(customer.mobilePhone)
    }

    private Boolean hasFinishedOnboarding(String mobilePhone) {
        return mobilePhone.asBoolean()
    }
}
