package com.asaas.service.api

import com.asaas.api.ApiTemporaryFileParser
import com.asaas.domain.file.TemporaryFile

import grails.transaction.Transactional

@Transactional
class ApiTemporaryFileService extends ApiBaseService {

    def temporaryFileService
    def apiResponseBuilderService

    def save(params) {
        Map fields = ApiTemporaryFileParser.parseRequestParams(params)

        TemporaryFile temporaryFile = temporaryFileService.save(getProviderInstance(params), fields.documentFile, true)

        if (temporaryFile.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(temporaryFile)
        }

        return apiResponseBuilderService.buildSuccess(ApiTemporaryFileParser.buildResponseItem(temporaryFile))
    }

}
