package com.asaas.service.customer.customerknownremoteip

import com.asaas.domain.customer.customerknownremoteip.CustomerKnownRemoteIp
import com.asaas.domain.customer.customerknownremoteip.TrustedCustomerKnownRemoteIpSchedule
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class TrustedCustomerKnownRemoteIpScheduleService {

    def customerKnownRemoteIpService

    public TrustedCustomerKnownRemoteIpSchedule saveIfNecessary(CustomerKnownRemoteIp customerKnownRemoteIp) {
        Boolean hasBeenScheduled = TrustedCustomerKnownRemoteIpSchedule.query([exists: true, customerKnownRemoteIpId: customerKnownRemoteIp.id, "scheduledDate[ge]": new Date().clearTime()]).get().asBoolean()
        if (hasBeenScheduled) return null

        final Integer scheduleDaysAhead = 7

        TrustedCustomerKnownRemoteIpSchedule trustedCustomerKnownRemoteIpSchedule = new TrustedCustomerKnownRemoteIpSchedule()
        trustedCustomerKnownRemoteIpSchedule.customerKnownRemoteIp = customerKnownRemoteIp
        trustedCustomerKnownRemoteIpSchedule.scheduledDate = CustomDateUtils.sumDays(new Date(), scheduleDaysAhead)
        trustedCustomerKnownRemoteIpSchedule.save(failOnError: true)

        return trustedCustomerKnownRemoteIpSchedule
    }

    public List<Long> process() {
        final Integer maxItemsPerCycle = 500
        final Integer dateLimitInDays = 7
        final Date today = new Date().clearTime()
        final Date dateLimit = CustomDateUtils.sumDays(today, -dateLimitInDays)

        Map searchParams = [
            column: "customerKnownRemoteIp.id",
            "customerKnownRemoteIpTrustedToCheckout": false,
            "scheduledDate[lt]": today,
            "scheduledDate[ge]": dateLimit,
            disableSort: true
        ]

        List<Long> customerKnownRemoteIpIdList = TrustedCustomerKnownRemoteIpSchedule.query(searchParams).list(max: maxItemsPerCycle)
        if (!customerKnownRemoteIpIdList) return []

        customerKnownRemoteIpService.setAsTrustedToCheckoutInBatch(customerKnownRemoteIpIdList)

        return customerKnownRemoteIpIdList
    }
}
