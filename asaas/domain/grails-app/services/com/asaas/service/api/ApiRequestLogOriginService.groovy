package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.domain.api.ApiRequestLog
import com.asaas.domain.api.ApiRequestLogOrigin
import grails.transaction.Transactional

@Transactional
class ApiRequestLogOriginService {

    public ApiRequestLogOrigin save(ApiRequestLog apiRequestLog) {
        ApiRequestLogOrigin apiRequestLogOrigin = new ApiRequestLogOrigin()
        apiRequestLogOrigin.request = apiRequestLog
        apiRequestLogOrigin.origin = ApiBaseParser.getRequestOrigin()
        apiRequestLogOrigin.save(failOnError: true)

        return apiRequestLogOrigin
    }
}
