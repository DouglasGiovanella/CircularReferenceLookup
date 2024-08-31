package com.asaas.service.api

import com.asaas.api.ApiRevenueServiceRegisterParser
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiRevenueServiceRegisterService extends ApiBaseService {

    def revenueServiceRegisterService
    def apiResponseBuilderService

    def find(params) {
        try {
            Map fields = ApiRevenueServiceRegisterParser.parseRequestParams(params)

            if (!CpfCnpjUtils.validate(fields.cpfCnpj)) {
                return apiResponseBuilderService.buildErrorFromCode("invalid.cpfCnpj")
            }

            RevenueServiceRegister revenueServiceRegister

            if (CpfCnpjUtils.isCnpj(fields.cpfCnpj)) {
                revenueServiceRegister = revenueServiceRegisterService.findLegalPerson(fields.cpfCnpj)
            } else if (CpfCnpjUtils.isCpf(fields.cpfCnpj)) {
                if (fields.birthDate) {
                    revenueServiceRegister = revenueServiceRegisterService.findNaturalPerson(fields.cpfCnpj, fields.birthDate)
                } else {
                    return apiResponseBuilderService.buildErrorFromCode("invalid.birthDate")
                }
            }

            if (revenueServiceRegister.hasErrors()) {
                String errorCode = RevenueServiceRegister.parseQueryErrorCode(revenueServiceRegister.errors.allErrors.defaultMessage[0])

                return apiResponseBuilderService.buildErrorFromCode(errorCode)
            }

            return apiResponseBuilderService.buildSuccess(ApiRevenueServiceRegisterParser.buildResponseItem(revenueServiceRegister))
        } catch (Exception e) {
            AsaasLogger.error("ApiRevenueServiceRegisterService.find >>> Erro ao tentar buscar o revenueServiceRegister do cliente [${(getProvider(params) ?: params.distinctId)}] ", e)
            return apiResponseBuilderService.buildErrorFromCode("revenueServiceRegister.errors.uncaughtException")
        }
    }
}
