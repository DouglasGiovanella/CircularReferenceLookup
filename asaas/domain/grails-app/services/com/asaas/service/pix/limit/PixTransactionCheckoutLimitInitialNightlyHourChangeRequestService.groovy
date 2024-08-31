package com.asaas.service.pix.limit

import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestPeriod
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestRisk
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitInitialNightlyHourChangeRequestService {

    def pixTransactionCheckoutLimitChangeRequestService

    public PixTransactionCheckoutLimitChangeRequest save(Customer customer, Integer initialNightlyHourRequested, Map requestInfoParams) {
        PixTransactionCheckoutLimitChangeRequest validatePixTransactionCheckoutLimitChangeRequest = validate(initialNightlyHourRequested)
        if (validatePixTransactionCheckoutLimitChangeRequest.hasErrors()) return validatePixTransactionCheckoutLimitChangeRequest

        validatePixTransactionCheckoutLimitChangeRequest = pixTransactionCheckoutLimitChangeRequestService.validateCriticalActionAuthorizationToken(customer, requestInfoParams)
        if (validatePixTransactionCheckoutLimitChangeRequest.hasErrors()) return validatePixTransactionCheckoutLimitChangeRequest

        Map requestInfo = [:]
        requestInfo.previousLimit = PixTransactionCheckoutLimit.getInitialNightlyHourConfig(customer)
        requestInfo.requestedLimit = initialNightlyHourRequested
        requestInfo.period = PixTransactionCheckoutLimitChangeRequestPeriod.NIGHTLY
        requestInfo.type = PixTransactionCheckoutLimitChangeRequestType.CHANGE_NIGHTLY_HOUR
        requestInfo.risk = PixTransactionCheckoutLimitChangeRequestRisk.LOW

        return pixTransactionCheckoutLimitChangeRequestService.save(customer, requestInfo)
    }

    private PixTransactionCheckoutLimitChangeRequest validate(Integer initialNightlyHour) {
        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()

        if (!PixTransactionCheckoutLimit.VALID_TIMES_FOR_NIGHTLY_PERIOD.contains(initialNightlyHour)) {
            DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequest.validateNewNightlyPeriod.error.invalidHour", PixTransactionCheckoutLimit.VALID_TIMES_FOR_NIGHTLY_PERIOD))
        }

        return pixTransactionCheckoutLimitChangeRequest
    }
}
