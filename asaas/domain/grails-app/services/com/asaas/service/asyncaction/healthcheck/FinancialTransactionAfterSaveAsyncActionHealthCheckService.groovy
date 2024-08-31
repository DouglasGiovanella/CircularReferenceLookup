package com.asaas.service.asyncaction.healthcheck

import com.asaas.domain.asyncAction.FinancialTransactionAfterSaveAsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional
import java.util.concurrent.TimeUnit

@Transactional
class FinancialTransactionAfterSaveAsyncActionHealthCheckService {

    public Boolean checkPendingQueueDelay() {
        final Integer delayInSeconds = 60
        final Integer criticalDelayInSeconds = TimeUnit.MINUTES.toSeconds(5).toInteger()

        Date maxLastUpdated = CustomDateUtils.sumSeconds(new Date(), -1 * delayInSeconds)

        Date minLastUpdated = FinancialTransactionAfterSaveAsyncAction.minPendingLastUpdate(["lastUpdated[le]": maxLastUpdated]).get()
        if (!minLastUpdated) return true

        Integer diffInSeconds = CustomDateUtils.calculateDifferenceInSeconds(minLastUpdated, new Date())
        if (diffInSeconds >= criticalDelayInSeconds) return false

        AsaasLogger.warn("FinancialTransactionAfterSaveAsyncActionHealthCheckService >> Existem asyncActions pendentes a mais de ${delayInSeconds} segundos na fila.")

        return true
    }
}
