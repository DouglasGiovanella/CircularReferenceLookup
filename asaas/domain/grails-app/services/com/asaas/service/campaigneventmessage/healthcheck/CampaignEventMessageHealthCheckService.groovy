package com.asaas.service.campaigneventmessage.healthcheck

import com.asaas.campaignevent.CampaignEventStatus
import com.asaas.domain.campaignevent.CampaignEventMessage
import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class CampaignEventMessageHealthCheckService {

    public Boolean checkOutboxDelayed() {
        final Integer toleranceSeconds = 180
        Date oldestEventDate = CampaignEventMessage.query([column: "dateCreated", order: "asc", status: CampaignEventStatus.PENDING]).get() as Date

        return !isQueueDelayed(oldestEventDate, toleranceSeconds)
    }

    private Boolean isQueueDelayed(Date oldestDate, Integer toleranceSeconds) {
        if (!oldestDate) return false

        Long oldestEventInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - oldestDate.getTime())
        Boolean delayDetected = oldestEventInSeconds > toleranceSeconds

        return delayDetected
    }
}
