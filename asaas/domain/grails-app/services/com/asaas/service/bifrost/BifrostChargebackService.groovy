package com.asaas.service.bifrost

import com.asaas.domain.asaascard.AsaasCard
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.api.BifrostManager

import grails.transaction.Transactional

@Transactional
class BifrostChargebackService {

    public void save(AsaasCard asaasCard, Long externalId, String reason) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/cards/${asaasCard.id}/statement/${externalId}/chargebackByTrasactionId", [reason: reason?.trim()])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }
}
