package com.asaas.service.payment.paymentaftercreditevent

import com.asaas.domain.payment.PaymentAfterCreditEvent
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class PaymentAfterCreditEventHealthCheckService {

    public Boolean checkPendingQueueDelay() {
        final Integer toleranceInSeconds = 60
        final Integer criticalToleranceInSeconds = TimeUnit.MINUTES.toSeconds(15).toInteger()

        Date maxLastUpdated = CustomDateUtils.sumSeconds(new Date(), -1 * toleranceInSeconds)

        Date minLastUpdated = PaymentAfterCreditEvent.minPendingLastUpdate(["lastUpdated[le]": maxLastUpdated]).get()
        if (!minLastUpdated) return true

        Integer diffInSeconds = CustomDateUtils.calculateDifferenceInSeconds(minLastUpdated, new Date())

        if (diffInSeconds >= criticalToleranceInSeconds) return false

        AsaasLogger.warn("PaymentAfterCreditEventHealthCheckService.checkPendingQueueDelay >>> Delay de mais de ${toleranceInSeconds} segundos na fila de processamento pós-crédito. ")

        return true
    }
}
