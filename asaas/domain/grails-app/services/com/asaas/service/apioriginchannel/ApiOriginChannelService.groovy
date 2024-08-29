package com.asaas.service.apioriginchannel

import com.asaas.domain.apioriginchannel.ApiOriginChannel

import grails.transaction.Transactional

import groovy.transform.CompileStatic

@CompileStatic
@Transactional
class ApiOriginChannelService {

    public ApiOriginChannel save(String name, Boolean useAuthentication) {
        ApiOriginChannel apiOriginChannel = new ApiOriginChannel()
        apiOriginChannel.name = name
        apiOriginChannel.useAuthentication = useAuthentication
        apiOriginChannel.publicId = UUID.randomUUID().toString()
        apiOriginChannel.save(failOnError: true)

        return apiOriginChannel
    }
}
