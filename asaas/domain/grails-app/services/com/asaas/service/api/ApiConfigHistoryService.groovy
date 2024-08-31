package com.asaas.service.api

import com.asaas.domain.api.ApiConfig
import com.asaas.domain.api.ApiConfigHistory
import com.asaas.domain.customer.Customer
import com.asaas.user.UserUtils
import grails.transaction.Transactional

@Transactional
class ApiConfigHistoryService {

    public ApiConfigHistory save(ApiConfig apiConfig) {
        ApiConfigHistory apiConfigHistory = new ApiConfigHistory()
        apiConfigHistory.user = UserUtils.getCurrentUser()
        apiConfigHistory.apiConfig = apiConfig
        apiConfigHistory.customer = apiConfig.provider
        return apiConfigHistory.save(failOnError: true)
    }
}
