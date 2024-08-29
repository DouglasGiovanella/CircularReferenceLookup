package com.asaas.service.integration.pix

import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.accountConfirmedFraud.HermesSaveAccountConfirmedFraudRequestDTO
import com.asaas.pix.adapter.accountConfirmedFraud.SaveAccountConfirmedFraudAdapter

import grails.transaction.Transactional

@Transactional
class HermesAccountConfirmedFraudManagerService {

    public Map save(SaveAccountConfirmedFraudAdapter saveAccountConfirmedFraudAdapter) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accountConfirmedFraud/save", new HermesSaveAccountConfirmedFraudRequestDTO(saveAccountConfirmedFraudAdapter).properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        return [success: false, errorMessage: hermesManager.errorMessage]
    }

    public Map cancel(Long customerId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accountConfirmedFraud/${customerId}/cancel", null, null)

        if (hermesManager.isSuccessful()) return [success: true]

        return [success: false, errorMessage: hermesManager.errorMessage]
    }
}
