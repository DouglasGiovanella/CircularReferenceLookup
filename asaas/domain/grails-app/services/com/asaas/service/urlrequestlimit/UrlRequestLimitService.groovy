package com.asaas.service.urlrequestlimit

import com.asaas.api.ApiBaseParser
import com.asaas.ratelimit.RateLimitBypassValidator
import com.asaas.domain.urlrequestlimit.UrlRequestLimit
import com.asaas.domain.user.User
import com.asaas.urllimitrequest.UrlRequestLimitType
import com.asaas.utils.RequestUtils
import com.asaas.utils.UrlRequestLimitCache

import grails.transaction.Transactional

@Transactional
class UrlRequestLimitService {

    def blockedCustomerUrlCacheService
    def blockedCustomerUrlService
    def rateLimitService
    def urlRequestService

    @Deprecated
    public Map processRequest(String controllerName, String actionName, User user, UrlRequestLimitType urlRequestLimitType) {
        if (urlRequestLimitType.isRemoteIp()) throw new RuntimeException("Método obsoleto. Utilize a [rateLimitService.checkForRemoteIp]")

        if (RateLimitBypassValidator.shouldBypassCustomer(user.customer)) return null

        if (ApiBaseParser.isAsaasErp()) return null

        UrlRequestLimit urlRequestLimit = UrlRequestLimitCache.find(controllerName, actionName, urlRequestLimitType)
        if (!urlRequestLimit) return null

        Date blockedCustomerReleaseDate = blockedCustomerUrlCacheService.getReleaseDateIfExists(user.customer.id, controllerName, actionName)
        if (blockedCustomerReleaseDate && blockedCustomerReleaseDate > new Date()) return [blocked: true, blockedReleaseDate: blockedCustomerReleaseDate]

        String remoteIp = RequestUtils.getRemoteIp()
        if (urlRequestLimit.saveUrlRequest) urlRequestService.save(controllerName, actionName, remoteIp, user)

        Map rateLimit = rateLimitService.limitHasBeenExceeded(urlRequestLimit, user.customer.id.toString())
        if (rateLimit.limitExceeded && urlRequestLimit.block) blockedCustomerUrlService.save(user.customer, urlRequestLimit.controller, urlRequestLimit.action)

        return rateLimit
    }
}
