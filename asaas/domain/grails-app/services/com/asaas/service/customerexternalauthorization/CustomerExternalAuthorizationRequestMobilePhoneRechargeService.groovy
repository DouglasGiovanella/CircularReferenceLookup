package com.asaas.service.customerexternalauthorization

import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestMobilePhoneRecharge

import grails.transaction.Transactional
import groovy.json.JsonSlurper

@Transactional
class CustomerExternalAuthorizationRequestMobilePhoneRechargeService {

    def mobilePhoneRechargeService

    public void approve(CustomerExternalAuthorizationRequestMobilePhoneRecharge externalAuthorizationRequestMobilePhoneRecharge) {
        mobilePhoneRechargeService.onExternalAuthorizationApproved(externalAuthorizationRequestMobilePhoneRecharge.mobilePhoneRecharge)
    }

    public void refuse(CustomerExternalAuthorizationRequestMobilePhoneRecharge externalAuthorizationRequestMobilePhoneRecharge) {
        mobilePhoneRechargeService.onExternalAuthorizationRefused(externalAuthorizationRequestMobilePhoneRecharge.mobilePhoneRecharge)
    }

    public Map buildRequestData(Long externalAuthorizationRequestId) {
        String eventData = CustomerExternalAuthorizationRequestMobilePhoneRecharge.query([column: "data", externalAuthorizationRequestId: externalAuthorizationRequestId]).get()

        return [mobilePhoneRecharge: new JsonSlurper().parseText(eventData)]
    }
}
