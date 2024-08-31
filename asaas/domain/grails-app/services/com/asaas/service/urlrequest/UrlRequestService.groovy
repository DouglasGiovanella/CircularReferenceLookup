package com.asaas.service.urlrequest

import com.asaas.domain.urlrequestlimit.UrlRequest
import com.asaas.domain.user.User
import grails.transaction.Transactional

@Transactional
class UrlRequestService {

    public void save(String controller, String action, String remoteIp, User user) {
        UrlRequest urlRequest = new UrlRequest()
        urlRequest.controller = controller
        urlRequest.action = action
        urlRequest.remoteIp = remoteIp
        urlRequest.user = user
        urlRequest.customerId = user?.customerId
        urlRequest.dateCreated = new Date()
        urlRequest.save(flush: true, failOnError: true)
    }
}
