package com.asaas.service.payment.paymentafterconfirmevent

import com.asaas.domain.payment.PaymentAfterConfirmEvent
import com.asaas.log.AsaasLogger
import com.asaas.paymentafterconfirmevent.PaymentAfterConfirmEventType
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class PaymentAfterConfirmEventHealthCheckService {

    public Boolean checkPendingQueueDelay(PaymentAfterConfirmEventType eventType) {
        final Integer toleranceInSeconds = 60
        final Integer criticalToleranceInSeconds = TimeUnit.MINUTES.toSeconds(15).toInteger()

        Date maxLastUpdated = CustomDateUtils.sumSeconds(new Date(), -1 * toleranceInSeconds)

        Date minLastUpdated = PaymentAfterConfirmEvent.minPendingLastUpdate(["type": eventType, "lastUpdated[le]": maxLastUpdated]).get()
        if (!minLastUpdated) return true

        Integer diffInSeconds = CustomDateUtils.calculateDifferenceInSeconds(minLastUpdated, new Date())

        if (diffInSeconds >= criticalToleranceInSeconds) return false

        AsaasLogger.warn("PaymentAfterConfirmEventHealthCheckService.checkPendingQueueDelay >>> Delay de mais de ${toleranceInSeconds} segundos na fila de processamento pós-confirmação. ")

        return true
    }
}
