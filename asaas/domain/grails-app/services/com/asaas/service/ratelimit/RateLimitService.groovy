package com.asaas.service.ratelimit

import com.asaas.domain.urlrequestlimit.UrlRequestLimit
import com.asaas.log.AsaasLogger
import com.asaas.ratelimit.RateLimitValidationConfig
import com.asaas.redis.RedissonProxy
import com.asaas.utils.CustomDateUtils

import javax.servlet.http.HttpServletResponse
import java.util.concurrent.TimeUnit

import org.redisson.api.RAtomicLong

class RateLimitService {

    def blockedIpService
    def blockedIpCacheService
    def urlRequestService

    public Map checkForRemoteIp(RateLimitValidationConfig validationConfig) {
        Date blockedRemoteIpReleaseDate = blockedIpCacheService.getReleaseDateIfExists(validationConfig.remoteIp)
       if (blockedRemoteIpReleaseDate && blockedRemoteIpReleaseDate > new Date()) return [blocked: true, blockedReleaseDate: blockedRemoteIpReleaseDate]

        if (validationConfig.saveRequest) urlRequestService.save(validationConfig.controllerName, validationConfig.actionName, validationConfig.remoteIp, null)

        Map rateLimitCheckResult = check(validationConfig)
        if (rateLimitCheckResult.limitExceeded && validationConfig.block) blockedIpService.save(validationConfig.remoteIp, validationConfig.controllerName, validationConfig.actionName)

        return rateLimitCheckResult
    }

    @Deprecated
    public Map limitHasBeenExceeded(UrlRequestLimit urlRequestLimit, String key) {
        try {
            return buildRateLimit(urlRequestLimit.controller, urlRequestLimit.action, urlRequestLimit.maxRequests, urlRequestLimit.period, key)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.limitHasBeenExceeded >> Erro ao verificar rate limit [urlRequestLimit.id: ${urlRequestLimit.id}, key: ${key}]", exception)
            return buildRateLimitResponseMap(0, 0, 0)
        }
    }

    @Deprecated
    public void fillResponseHeaders(HttpServletResponse response, String limit, String remaining, String resetInSeconds) {
        response.setHeader('RateLimit-Limit', limit)
        response.setHeader('RateLimit-Remaining', remaining)
        response.setHeader('RateLimit-Reset', resetInSeconds)
    }

    @Deprecated
    public void addRetryAfterResponseHeader(HttpServletResponse response, Date retryAfterDate) {
        response.setHeader('Retry-After', CustomDateUtils.calculateDifferenceInSeconds(new Date(), retryAfterDate).toString())
    }

    private Map check(RateLimitValidationConfig validationConfig) {
        try {
            if (!validationConfig.mustValidate()) return buildRateLimitResponseMap(0, 0, 0)
            return buildRateLimit(validationConfig.controllerName, validationConfig.actionName, validationConfig.maxRequests, validationConfig.periodInSeconds, validationConfig.key)
        } catch (Exception exception) {
            AsaasLogger.error("RateLimitService.check >> Erro ao validar endpoints [${validationConfig.controllerName}.${validationConfig.actionName}] para o key [${validationConfig.key}]", exception)
            return buildRateLimitResponseMap(0, 0, 0)
        }
    }

    private Map buildRateLimit(String controllerName, String actionName, Integer maxRequests, Integer period, String key) {
        if (!RedissonProxy.instance.isConnected()) return buildRateLimitResponseMap(0, 0, 0)

        String redisKey = "${key}:${controllerName}:${actionName}:${CustomDateUtils.getInstanceOfCalendar().get(Calendar.MINUTE)}"
        RAtomicLong atomicLong = RedissonProxy.instance.getClient().getAtomicLong(redisKey)

        Long usedRate = atomicLong.incrementAndGet()

        if (atomicLong.remainTimeToLive() < 0) {
            atomicLong.expire(period, TimeUnit.SECONDS)
        }

        return buildRateLimitResponseMap(maxRequests, usedRate, (atomicLong.remainTimeToLive() / 1000).toInteger())
    }

    private Map buildRateLimitResponseMap(Integer maxRequests, Long usedRate, Integer resetInSeconds) {
        Map rateLimit = [:]
        rateLimit.limitExceeded = (usedRate > maxRequests)
        rateLimit.limit = maxRequests
        rateLimit.remaining = Math.max(maxRequests - usedRate, 0)
        rateLimit.resetInSeconds = resetInSeconds

        return rateLimit
    }
}
