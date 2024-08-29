package com.asaas.service.usersecurityeventtracking

import grails.transaction.Transactional

@Transactional
class UserSecurityEventTrackingService {

    def asaasSegmentioService

    public void save(Map params, String resourceId, Long userId, Long customerId) {
        final String eventName = "user_security_event"

        Map trackInfo = [:]

        trackInfo.userId = userId
        trackInfo.controllerName = params.controller
        trackInfo.action = params.action
        trackInfo.resourceId = resourceId

        asaasSegmentioService.track(customerId, eventName, trackInfo)
    }
}
