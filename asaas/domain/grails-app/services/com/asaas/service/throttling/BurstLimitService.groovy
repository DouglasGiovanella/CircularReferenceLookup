package com.asaas.service.throttling

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.log.AsaasLogger
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.redisson.api.RBucket
import org.redisson.codec.TypedJsonJacksonCodec

import java.util.concurrent.TimeUnit

@Transactional
class BurstLimitService {

    def asyncActionService
    def customerMessageService

    public void registerMailToSendIfNecessary(Long customerId, Integer concurrentRequestLimit, Date concurrentRequestExceededDate) {
        RBucket bucket = getBucket(customerId)
        if (bucket.get()) return

        final Integer ttlCacheInHours = 24
        bucket.set(true, ttlCacheInHours, TimeUnit.HOURS)

        asyncActionService.saveSendMailBurstLimitExceeded(customerId, concurrentRequestLimit, concurrentRequestExceededDate)
        AsaasLogger.info("BurstLimitService.registerMailToSendIfNecessary > AsyncAction registrada para o customer[${customerId}]")
    }

    public void processLimitExceededMail() {
        final Integer maxNumberOfGroupIdPerExecution = 50
        List<String> groupIdList = AsyncAction.oldestPending([distinct: "groupId", disableSort: true, type: AsyncActionType.SEND_MAIL_API_BURST_LIMIT_EXCEEDED]).list(max: maxNumberOfGroupIdPerExecution)
        if (!groupIdList) return

        final Integer flushEvery = 5
        Utils.forEachWithFlushSession(groupIdList, flushEvery, { String groupId ->
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncActionGroupId = AsyncAction.oldestPending(type: AsyncActionType.SEND_MAIL_API_BURST_LIMIT_EXCEEDED, groupId: groupId).get()
                final Map asyncActionData = asyncActionGroupId.getDataAsMap()
                final Long customerId = asyncActionData.customerId
                final Integer concurrentRequestLimit = asyncActionData.concurrentRequestLimit
                final Date concurrentRequestExceededDate = CustomDateUtils.fromString(asyncActionData.concurrentRequestExceededDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)

                customerMessageService.notifyAboutBurstLimitExceeded(customerId, concurrentRequestLimit, concurrentRequestExceededDate)
                asyncActionService.deleteAllPendingSendMailBurstLimitExceeded(groupId)
                AsaasLogger.info("BurstLimitService.processLimitExceededMail > E-mail enviado para o customer[${customerId}]")
            }, [logErrorMessage: "BurstLimitService.processLimitExceededMail > Falha ao enviar e-mail ao cliente[${groupId}]"])
        })
    }

    private RBucket getBucket(Long customerId) {
        final String cacheName = "AsyncAction:existsByTypeAndStatusAndGroupId"
        final String cacheKey = "${AsyncActionType.SEND_MAIL_API_BURST_LIMIT_EXCEEDED}:${AsyncActionStatus.PENDING}:${customerId}"
        return RedissonProxy.instance.getClient().getBucket("${cacheName}:${cacheKey}", new TypedJsonJacksonCodec(Boolean))
    }
}
