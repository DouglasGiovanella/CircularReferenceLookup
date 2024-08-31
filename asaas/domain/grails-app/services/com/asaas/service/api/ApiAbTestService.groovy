package com.asaas.service.api

import com.asaas.api.ApiAbTestParser

import grails.transaction.Transactional

@Transactional
class ApiAbTestService extends ApiBaseService {

    def abTestService

    def chooseVariant(params) {
        Map fields = ApiAbTestParser.parseRequestParams(params)

        String variant = abTestService.chooseVariant(fields.abTestName, fields.customer, fields.distinctId, fields.platform)

        return ApiAbTestParser.buildResponseItem(fields.abTestName, variant, fields.platform)
    }

    def saveAnonymousUserVariant(params) {
        Map fields = ApiAbTestParser.parseRequestParams(params)

        abTestService.saveAnonymousUserVariant(fields.distinctId, fields.abTestName, fields.variant, fields.platform)

        return [success: true]
    }
}
