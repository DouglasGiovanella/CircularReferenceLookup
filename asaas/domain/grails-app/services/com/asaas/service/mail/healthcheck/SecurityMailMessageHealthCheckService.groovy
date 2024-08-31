package com.asaas.service.mail.healthcheck

import com.asaas.domain.email.AsaasSecurityMailMessage
import com.asaas.email.AsaasMailMessageStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class SecurityMailMessageHealthCheckService {

    public Boolean isErrorRateAboveLimit() {
        final BigDecimal limitErrorRate = 10.0
        final Integer mailAmountToRate = 300

        List<AsaasMailMessageStatus> lastNotPendingMailStatusList = AsaasSecurityMailMessage.query([column: "status",
                                                                                                    "status[ne]": AsaasMailMessageStatus.PENDING]).list(max: mailAmountToRate)
        Integer totalCount = lastNotPendingMailStatusList.size()
        Integer errorCount = lastNotPendingMailStatusList.count { it == AsaasMailMessageStatus.ERROR }

        if (errorCount && totalCount) {
            BigDecimal errorRate = (errorCount / totalCount) * 100

            if (errorRate > limitErrorRate) {
                return true
            }
        }

        return false
    }

    public Boolean hasQueueDelay() {
        final Integer toleranceInMinutes = 3
        final Date toleranceDate = CustomDateUtils.sumMinutes(new Date(), -toleranceInMinutes)

        Date oldestPendingMailDateCreated = AsaasSecurityMailMessage.query([column: "dateCreated",
                                                                            "status": AsaasMailMessageStatus.PENDING,
                                                                            "sort": "id",
                                                                            "order": "asc"]).get()

        return oldestPendingMailDateCreated?.before(toleranceDate)
    }
}
