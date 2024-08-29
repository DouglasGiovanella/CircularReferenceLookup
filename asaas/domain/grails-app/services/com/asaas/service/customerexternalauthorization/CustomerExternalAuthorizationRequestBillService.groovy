package com.asaas.service.customerexternalauthorization

import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestBill

import grails.transaction.Transactional
import groovy.json.JsonSlurper

@Transactional
class CustomerExternalAuthorizationRequestBillService {

    def billService

    public void approve(CustomerExternalAuthorizationRequestBill externalAuthorizationRequestBill) {
        billService.onExternalAuthorizationApproved(externalAuthorizationRequestBill.bill)
    }

    public void refuse(CustomerExternalAuthorizationRequestBill externalAuthorizationRequestBill) {
        billService.onExternalAuthorizationRefused(externalAuthorizationRequestBill.bill)
    }

    public Map buildRequestData(Long externalAuthorizationRequestId) {
        String eventData = CustomerExternalAuthorizationRequestBill.query([column: "data", externalAuthorizationRequestId: externalAuthorizationRequestId]).get()

        return [bill: new JsonSlurper().parseText(eventData)]
    }
}
