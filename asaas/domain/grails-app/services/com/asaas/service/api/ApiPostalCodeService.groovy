package com.asaas.service.api

import com.asaas.api.ApiPostalCodeParser
import com.asaas.domain.postalcode.PostalCode

import grails.transaction.Transactional

@Transactional
class ApiPostalCodeService extends ApiBaseService {

    def apiResponseBuilderService

    public Map show(Map params) {
        PostalCode postalCode = PostalCode.find(params.postalCode)

        if (!postalCode.asBoolean()) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildSuccess(ApiPostalCodeParser.buildResponseItem(postalCode))
    }

}