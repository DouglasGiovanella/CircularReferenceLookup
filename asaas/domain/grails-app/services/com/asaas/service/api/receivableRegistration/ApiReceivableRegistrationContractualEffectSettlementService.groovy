package com.asaas.service.api.receivableRegistration

import com.asaas.api.receivableRegistration.ApiReceivableRegistrationContractualEffectSettlementParser
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiReceivableRegistrationContractualEffectSettlementService extends ApiBaseService {

    def apiResponseBuilderService

    public Map find(Map params) {
        CercContractualEffectSettlement cercContractualEffectSettlement = CercContractualEffectSettlement.query([id: params.long("id"), customerId: getProvider(params)]).get()

        if (!cercContractualEffectSettlement) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiReceivableRegistrationContractualEffectSettlementParser.buildResponseItem(cercContractualEffectSettlement))
    }
}
