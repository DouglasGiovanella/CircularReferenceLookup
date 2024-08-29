package com.asaas.service.api

import com.asaas.api.ApiChargebackParser
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.payment.Payment
import com.asaas.exception.ResourceNotFoundException
import grails.transaction.Transactional

@Transactional
class ApiChargebackService extends ApiBaseService {

    def apiResponseBuilderService

    public Map find(Map params) {
        Chargeback chargeback = Chargeback.find(params.id, getProviderInstance(params))
        return apiResponseBuilderService.buildSuccess(ApiChargebackParser.buildResponseItem(chargeback))
    }

    public Map findByPaymentId(Map params) {
        Payment payment = Payment.find(params.id, getProvider(params))

        Chargeback chargeback = payment.getChargeback()

        if (!chargeback) throw new ResourceNotFoundException("O chargeback do pagamento ${params.id} do Asaas não foi encontrado.")

        return apiResponseBuilderService.buildSuccess(ApiChargebackParser.buildResponseItem(chargeback))
    }
}
