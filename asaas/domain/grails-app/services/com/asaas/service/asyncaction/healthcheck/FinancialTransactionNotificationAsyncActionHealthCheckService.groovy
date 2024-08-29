package com.asaas.service.asyncaction.healthcheck

import com.asaas.domain.asaaserp.AsaasErpFinancialTransactionNotification
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

import java.util.concurrent.TimeUnit

@Transactional
class FinancialTransactionNotificationAsyncActionHealthCheckService {

    public Boolean checkPendingQueueDelay() {
        final Integer mediumDelayInSeconds = TimeUnit.MINUTES.toSeconds(5).toInteger()
        final Integer criticalDelayInSeconds = TimeUnit.MINUTES.toSeconds(10).toInteger()
        Date maxDateCreated = CustomDateUtils.sumSeconds(new Date(), -1 * mediumDelayInSeconds)

        Date minDateCreated = AsaasErpFinancialTransactionNotification.minPendingDateCreated(["dateCreated[le]": maxDateCreated]).get()
        if (!minDateCreated) return true

        Integer diffInSeconds = CustomDateUtils.calculateDifferenceInSeconds(minDateCreated, new Date())
        if (diffInSeconds >= criticalDelayInSeconds) return false

        AsaasLogger.warn("FinancialTransactionNotificationAsyncActionHealthCheckService >> Existem asyncActions pendentes a mais de ${diffInSeconds} segundos na fila.")

        return true
    }

}
