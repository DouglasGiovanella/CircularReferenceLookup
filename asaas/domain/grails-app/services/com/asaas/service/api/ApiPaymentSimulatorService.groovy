package com.asaas.service.api

import com.asaas.api.ApiPaymentSimulatorParser
import com.asaas.billinginfo.BillingType
import com.asaas.paymentSimulator.PaymentSimulatorVO
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiPaymentSimulatorService extends ApiBaseService {

    def apiResponseBuilderService
    def installmentService
    def paymentSimulatorService

    public Map simulate(Map params) {
        Map parsedFields = ApiPaymentSimulatorParser.parseSimulationParams(params)

        PaymentSimulatorVO paymentSimulatorVO = paymentSimulatorService.simulate(getProviderInstance(params), parsedFields.value, parsedFields.installmentCount, parsedFields.receiptType, parsedFields.billingTypeList)

        return apiResponseBuilderService.buildSuccess(ApiPaymentSimulatorParser.buildResponseItem(paymentSimulatorVO))
    }

    public Map getInstallmentOptions(Map params) {
        BillingType billingType = BillingType.convert(params.billingType)?.toRequestAPI()
        if (!billingType) return apiResponseBuilderService.buildErrorFrom("invalid_billingType", "O billingType informado é inválido")

        Integer maxNumberOfInstallments = null
        List<Map> installmentOptionList = installmentService.buildInstallmentOptionList(getProviderInstance(params), Utils.toBigDecimal(params.value), billingType, maxNumberOfInstallments)

        return apiResponseBuilderService.buildSuccess(ApiPaymentSimulatorParser.buildInstallmentOptionsResponse(installmentOptionList))
    }
}
