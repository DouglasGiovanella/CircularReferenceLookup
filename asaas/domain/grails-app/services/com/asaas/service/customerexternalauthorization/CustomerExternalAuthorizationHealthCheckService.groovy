package com.asaas.service.customerexternalauthorization

import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequestStatus
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestStatus
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationProcessedRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class CustomerExternalAuthorizationHealthCheckService {

    public Boolean checkPendingRequestQueueDelay() {
        final Integer toleranceMinutes = 3
        final Integer maxIdListSize = 10
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, toleranceMinutes * -1)

        Boolean hasDelay = CustomerExternalAuthorizationRequestConfig.createCriteria().list(max: maxIdListSize) {
            projections {
                property "id"
            }

            eq("enabled", true)
            eq("deleted", false)

            exists CustomerExternalAuthorizationRequest.where {
                setAlias("customerExternalAuthorizationRequest")
                eqProperty("customerExternalAuthorizationRequest.config.id", "this.id")
                eq("status", CustomerExternalAuthorizationRequestStatus.PENDING)
                lt("dateCreated", toleranceInstant)
                eq("deleted", false)
            }.id()
        }.asBoolean()

        return !hasDelay
    }

    public Boolean checkPendingProcessedQueueDelay() {
        final Integer toleranceMinutes = 3
        final Date instant = new Date()
        Date toleranceInstant = CustomDateUtils.sumMinutes(instant, toleranceMinutes * -1)

        Map query = [
            "dateCreated[lt]": toleranceInstant,
            disableSort: true,
            status: CustomerExternalAuthorizationProcessedRequestStatus.PENDING,
            exists: true
        ]

        return !CustomerExternalAuthorizationProcessedRequest.query(query).get().asBoolean()
    }

}
