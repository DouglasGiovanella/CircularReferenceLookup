package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional
import org.redisson.api.RBucket
import org.redisson.client.RedisException

import java.util.concurrent.TimeUnit

@Transactional(noRollbackFor = RedisException)
class FinancialStatementCacheService {

    public Long getCachedFinancialStatementId(FinancialStatementType financialStatementType, Date statementDate) {
        RedissonProxy redissonProxy = RedissonProxy.instance
        if (!redissonProxy.isConnected()) return null

        String cacheKey = buildFinancialStatementIdCacheKey(financialStatementType, statementDate)

        RBucket<Long> bucket = redissonProxy.getBucket(cacheKey, Long)
        Boolean cachedIdNotExists = bucket.trySet(-1L, 60, TimeUnit.SECONDS)
        if (cachedIdNotExists) return null

        Long cachedId = bucket.get()
        return cachedId
    }

    public void setCachedFinancialStatementId(FinancialStatementType financialStatementType, Date statementDate, Long financialStatementId) {
        RedissonProxy redissonProxy = RedissonProxy.instance
        if (!redissonProxy.isConnected()) return

        String cacheKey = buildFinancialStatementIdCacheKey(financialStatementType, statementDate)
        RBucket<Long> bucket = redissonProxy.getBucket(cacheKey, Long)
        bucket.set(financialStatementId, 24, TimeUnit.HOURS)
    }

    public isAwaitingFinancialStatementGeneratedByAnotherThread(Long financialStatementId) {
        return financialStatementId < 0
    }

    @Cacheable(value = "FinancialStatement:findId", key = "#type + ':' + #root.target.truncateDateKey(#statementDate) + ':' + #bank?.id")
    public Long findId(FinancialStatementType type, Date statementDate, Bank bank) {
        Map search = [:]
        search.column = "id"
        search.financialStatementType = type
        search.statementDate = statementDate
        if (bank) {
            search.bank = bank
        } else {
            search."bank[isNull]" = true
        }

        return FinancialStatement.query(search).get()
    }

    public String truncateDateKey(Date statementDate) {
        Date clonedDate = statementDate.clone()
        return CustomDateUtils.fromDate(clonedDate, CustomDateUtils.DATABASE_DATE_FORMAT)
    }

    private String buildFinancialStatementIdCacheKey(FinancialStatementType financialStatementType, Date statementDate) {
        final String cacheName = "FinancialStatementCacheService:financialStatementId"
        final String cacheKey = "${financialStatementType}:${CustomDateUtils.formatDate(statementDate)}"

        return "$cacheName:$cacheKey"
    }
}
